package com.manuel.ours.sms

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.sms.BankRules
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.domain.model.TxnType
import org.junit.Before
import org.junit.Test

/**
 * Reading a bank the compiled table has never heard of.
 *
 * [RegionalBankTest] guards the *table*. This guards what happens when the table is
 * wrong anyway — which it will be, because a bank can register a new DLT header at any
 * time without telling anyone, and the household finds out only by noticing that money
 * stopped appearing.
 *
 * The message below is real. Federal Bank sends from `FEDBNK`, which the app knew, and
 * then sent this one from `FEDSMS`, which it did not — so it was discarded at the sender
 * gate before an amount was ever looked for. Same bank, same account, same phone.
 *
 * The rule this encodes: **a bank that names itself in the body identifies itself**, and
 * a message carrying an amount, a settled verb and an account reference is a bank alert
 * whether or not we recognise who sent it.
 */
class SenderDiscoveryTest {

    private val parser = SmsParser()

    /** The learned table is process-global, so each test starts from a clean one. */
    @Before
    fun reset() {
        BankRules.setTaughtSenders(emptyMap())
        BankRules.forgetDiscovered()
    }

    private val federal =
        "Dear Customer, Rs.52 credited to your A/c XX4657 on 06AUG2026 18:38:28. " +
            "BAL-Rs.3000.23-Federal Bank"

    // ─── The message that went missing ───────────────────────────────────────

    @Test
    fun `the FEDSMS credit is read, and attributed to Federal Bank`() {
        val result = parser.parse("AD-FEDSMS-S", federal, receivedAt = 0L)
        assertThat(result).isInstanceOf(SmsParser.Result.Expense::class.java)

        val txn = (result as SmsParser.Result.Expense).txn
        assertThat(txn.amountPaise).isEqualTo(5_200)
        assertThat(txn.type).isEqualTo(TxnType.CREDIT)
        assertThat(txn.accountTail).isEqualTo("4657")
        assertThat(txn.bank).isEqualTo("Federal Bank")
    }

    /**
     * `BAL-Rs.3000.23-Federal Bank`.
     *
     * Every other bank writes "Avl Bal: Rs.3000.23", so the balance clause expected a
     * space or a colon after the marker and stopped dead at the hyphen. Federal's
     * minimum balance is ₹3,000 and this account is 23 paise above it — precisely the
     * case where a balance the app failed to record is the one it most needed.
     */
    @Test
    fun `a hyphen-delimited balance is still a balance`() {
        val txn = (parser.parse("AD-FEDSMS-S", federal, 0L) as SmsParser.Result.Expense).txn
        assertThat(txn.balancePaise).isEqualTo(300_023)
    }

    @Test
    fun `the amount is the transaction, never the balance beside it`() {
        val txn = (parser.parse("AD-FEDSMS-S", federal, 0L) as SmsParser.Result.Expense).txn
        // ₹52 credited, not the ₹3,000.23 that follows it.
        assertThat(txn.amountPaise).isEqualTo(5_200)
    }

    /**
     * `Rs..01` — the bank's own typo, on a one-paise credit.
     *
     * Three of these are on the household's phone and all three used to be recorded as
     * **the balance**: the amount pattern needs a digit after the currency, `Rs..01`
     * offers a full stop, so the scan ran on and matched `Rs.3,000.48` inside
     * `BAL-Rs.3,000.48` — turning a penny credit into a three-thousand-rupee one. It only
     * stopped once the balance clause learned to read Federal's hyphen and mask it.
     *
     * Refusing is the right answer, not a shortfall. The true amount is a hundredth of a
     * rupee and unparseable as written; inventing one from the balance is how a ledger
     * gains money nobody received.
     */
    @Test
    fun `a malformed amount is refused rather than read off the balance`() {
        val result = parser.parse(
            "CP-FEDBNK-S",
            "Dear Customer, Rs..01 credited to your A/c XX4657 on 07JUN2026 20:47:09. " +
                "BAL-Rs.3000.48-Federal Bank",
            0L,
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
        assertThat((result as SmsParser.Result.Ignored).reason)
            .isEqualTo(SmsParser.Reason.NO_AMOUNT)
    }

    // ─── Learning, so it costs one message and not every message ─────────────

    /**
     * `FEDSMS` is now in the compiled table, because a header we know beats a header we
     * deduce every time. Discovery is what covers the *next* one, so it is tested with a
     * header the table genuinely does not have.
     */
    @Test
    fun `the discovered header is remembered for the next message`() {
        assertThat(BankRules.forSender("AD-NEWFED-S")).isNull()

        val first = parser.parse(
            "AD-NEWFED-S",
            "Dear Customer, Rs.52 credited to your A/c XX4657 on 06AUG2026 18:38:28. " +
                "BAL-Rs.3000.23-Federal Bank",
            0L,
        )
        assertThat(first).isInstanceOf(SmsParser.Result.Expense::class.java)

        // A later message from the same header no longer needs to name its bank.
        assertThat(BankRules.forSender("AD-NEWFED-S")?.bank).isEqualTo("Federal Bank")
        val plain = parser.parse(
            "AD-NEWFED-S",
            "Dear Customer, Rs.1,240.00 debited from A/c XX4657 on 07AUG2026 09:12:01.",
            0L,
        )
        assertThat(plain).isInstanceOf(SmsParser.Result.Expense::class.java)
        assertThat((plain as SmsParser.Result.Expense).txn.type).isEqualTo(TxnType.DEBIT)
    }

    @Test
    fun `a header is only learned from a message that actually parsed`() {
        // Bank-shaped words, but it is an OTP and must teach us nothing.
        parser.parse(
            "AD-NEWBNK-S",
            "123456 is your OTP for a debit of Rs.500 from A/c XX1111. Do not share.",
            0L,
        )
        assertThat(BankRules.forSender("AD-NEWBNK-S")).isNull()
    }

    // ─── The shape gate ──────────────────────────────────────────────────────

    /**
     * Bank-shaped, but naming no bank. Discarded — and this is the deliberate limit of
     * the feature, not an oversight.
     */
    @Test
    fun `an unknown header that names no bank is still ignored`() {
        val result = parser.parse(
            "AD-ZZZBNK-S",
            "Rs.899.00 debited from A/c XX7788 on 06AUG2026 11:02:44. Avl Bal Rs.4,120.50",
            0L,
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
    }

    /**
     * The six real messages that decided the design.
     *
     * Audited against 2,810 messages on the household's phone, 99 sender headers were
     * unrecognised. Six of them wrote something bank-shaped — an amount, a settled verb
     * and a masked number — and *none* of the six was a bank. A gate that asked only for
     * that shape read the EPF passbook line as ₹61,989 of income, and accepted a
     * phishing SMS advertising a shortened link.
     *
     * They are kept verbatim (digits masked) because invented examples would not have
     * caught any of them.
     */
    @Test
    fun `the bank-shaped messages that are not banks are all refused`() {
        val impostors = mapOf(
            "AD-EPFOHO-S" to
                "Dear 1234, your passbook balance against KRKTM****5678 is Rs. 61,989/-. " +
                "Contribution of Rs. 2,350/- for due month Jun-26 has been received",
            "AD-QCAMZN-S" to
                "Payment of Rs 135.00 using Apay balance is successful at A.in. Updated " +
                "balance is Rs 48.00. If not u? call 1800123456 - SMS via Pine Labs",
            "AD-IOCMKT-S" to
                "Thanks for filling 1.98 Ltrs Petrol for Rs. 210.00 @ IOCL Petrol Pump " +
                "NEW INDIA FUELS, KOTTAYAM. Your XTRAREWARDS Loyalty",
            "AD-AFTRDE-S" to
                "Dear user(1234567890) Lgr Bal ALL Received: Rs.5000 Date:22/6 Bill CFO " +
                "Balance Rs.100 click view bit.ly/4xPqOr1 -AfterTrade",
            "AD-AFNBRK-S" to
                "AFN Langrana CM-Trade Account(812***4567) in credited Rs.9000 for " +
                "Withdraw process is 9pm today Click be48.top/SN8-1234567890 .",
            "AD-MYNTRA-S" to
                "Dear Customer, your payment of Rs. 340 using Myntra Gift Card ****1234 " +
                "balance is successful. Updated Myntra Gift Card balance: Rs. 34.00",
        )
        impostors.forEach { (sender, body) ->
            assertThat(parser.parse(sender, body, 0L))
                .isInstanceOf(SmsParser.Result.Ignored::class.java)
        }
    }

    /**
     * "CRED" is an issuer in the table and also four letters inside *credited*.
     *
     * Matching bank names as substrings therefore identified almost every bank SMS ever
     * written as having been sent by CRED — including the phishing message above, which
     * is how the bug was found.
     */
    @Test
    fun `a bank name is matched as a word, not as a run of letters`() {
        assertThat(BankRules.bankNamedIn("Rs.500 credited to your account")).isNull()
        assertThat(BankRules.bankNamedIn("Rs.500 spent, -Federal Bank")).isEqualTo("Federal Bank")
    }

    /**
     * The gate has to be narrow or "never miss a bank" becomes "every shop is a bank".
     * An amount alone is not banking — a delivery notice has one too.
     */
    @Test
    fun `an unknown sender without the shape of a bank alert is still ignored`() {
        val notBanking = listOf(
            "Your order of Rs.499 has been shipped and will arrive tomorrow.",
            "Table for two confirmed at 8pm. See you soon!",
            "Recharge of Rs.239 successful. Enjoy unlimited calls.",
        )
        notBanking.forEach { body ->
            val result = parser.parse("AD-SHOPPY-S", body, 0L)
            assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
        }
    }

    @Test
    fun `a personal phone number is never a bank however the message reads`() {
        val result = parser.parse(
            "+919876543210",
            "Rs.500 debited from A/c XX1234 on 06AUG2026 10:00:00. Avl Bal Rs.900",
            0L,
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
        assertThat((result as SmsParser.Result.Ignored).reason)
            .isEqualTo(SmsParser.Reason.UNKNOWN_SENDER)
    }

    // ─── Not at the cost of what already worked ──────────────────────────────

    @Test
    fun `the known headers still resolve exactly as before`() {
        assertThat(BankRules.forSender("AD-FEDBNK-S")?.bank).isEqualTo("Federal Bank")
        assertThat(BankRules.forSender("AX-KGBANK-S")?.bank).isEqualTo("Kerala Gramin Bank")
        assertThat(BankRules.forSender("VM-ICICIT-S")?.bank).isEqualTo("ICICI Bank")
    }

    // ─── Utkarsh SuperCard ───────────────────────────────────────────────────

    /**
     * 297 messages on the household's phone, 251 of them card debits worth ₹44,037,
     * every one discarded. Utkarsh was in the table under `UTKBNK` and `UTKARS`; its
     * SuperCard sends from `UTKSPR`, which was not — the Kerala Gramin failure exactly,
     * on a different bank.
     */
    private fun superCardDebit(day: String) =
        "Dear MANUEL, your SuperCard 1234 debited for INR 64.00 on $day 06:12 PM " +
            "for UPI - 556677889900. To dispute call 18001234567 - Utkarsh SFBL"

    @Test
    fun `a SuperCard debit is read`() {
        val august = BankRules.AUGUST_2026 + 2 * 24 * 3_600_000L
        val txn = (
            parser.parse("VM-UTKSPR-S", superCardDebit("03 Aug"), august)
                as SmsParser.Result.Expense
            ).txn
        assertThat(txn.amountPaise).isEqualTo(6_400)
        assertThat(txn.type).isEqualTo(TxnType.DEBIT)
        assertThat(txn.bank).isEqualTo("Utkarsh SuperCard")
    }

    /**
     * The household chose to start counting this card from 1 August.
     *
     * Teaching the app a header it had been discarding for six months makes 251 old
     * messages readable at once. Posting them would move March through July by tens of
     * thousands of rupees each — months already read and reconciled. A parser fix must
     * not rewrite a past that people have already acted on.
     */
    @Test
    fun `a SuperCard debit from before August is deliberately left out`() {
        val july = BankRules.AUGUST_2026 - 3 * 24 * 3_600_000L
        val result = parser.parse("VM-UTKSPR-S", superCardDebit("29 Jul"), july)
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
        assertThat((result as SmsParser.Result.Ignored).reason)
            .isEqualTo(SmsParser.Reason.BEFORE_SENDER_START)
    }

    /** The floor is this one sender's, not a rule about time. */
    @Test
    fun `every other bank is unaffected by that floor`() {
        val july = BankRules.AUGUST_2026 - 30L * 24 * 3_600_000L
        val result = parser.parse(
            "AX-KGBANK-S",
            "Rs.500.00 debited from A/c XX3062 on 05JUL2026 10:00:00. Avl Bal Rs.9,000",
            july,
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Expense::class.java)
    }

    /**
     * Settling the card is not spending — the purchases behind it are already recorded
     * one by one, and counting the bill as well is the double-count that inflates a
     * month more than anything else. It hinged on the word: the check looked for
     * "credit card" and Utkarsh writes "SuperCard".
     */
    @Test
    fun `paying the SuperCard bill is a bill payment, not another expense`() {
        val txn = (
            parser.parse(
                "VM-UTKSPR-S",
                "We have received payment of INR 1,778.00 for your SuperCard ending 1234. " +
                    "Your available limit is now INR 1,800.00 -Utkarsh SFBL",
                BankRules.AUGUST_2026 + 2 * 24 * 3_600_000L,
            ) as SmsParser.Result.Expense
            ).txn
        assertThat(txn.kind).isEqualTo(SmsParser.Kind.CARD_BILL_PAYMENT)
    }

    @Test
    fun `a bank that names itself is preferred over the header we would have guessed`() {
        // The body is the stronger evidence: it says which bank, in words.
        parser.parse(
            "AD-QQBANK-S",
            "Rs.75.00 credited to your A/c XX4657 on 06AUG2026 18:38:28. " +
                "BAL-Rs.3075.23-Federal Bank",
            0L,
        )
        assertThat(BankRules.forSender("AD-QQBANK-S")?.bank).isEqualTo("Federal Bank")
    }
}
