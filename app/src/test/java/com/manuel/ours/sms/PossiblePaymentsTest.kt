package com.manuel.ours.sms

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.sms.BankRules
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.domain.model.TxnType
import org.junit.Before
import org.junit.Test

/**
 * A payment-shaped message from a sender nobody has vouched for becomes a question.
 *
 * The failures that cost this household most were silent: a bank registers a new TRAI
 * header, every message it sends is discarded before an amount is looked for, and nobody
 * finds out for months. `FEDSMS` cost a credit; `UTKSPR` cost 251 card debits worth
 * ₹44,037.
 *
 * The obvious fix — count anything payment-shaped — was built and measured against 2,810
 * real messages first, and read an EPF passbook line as ₹61,989 of income. So shape is
 * enough to **ask** and not enough to **count**, and this is where the asking happens.
 */
class PossiblePaymentsTest {

    private val parser = SmsParser()

    @Before
    fun reset() {
        BankRules.setTaughtSenders(emptyMap())
        BankRules.forgetDiscovered()
    }

    private fun parse(sender: String, body: String) = parser.parse(sender, body, 1_000L)

    // ─── What becomes a question ─────────────────────────────────────────────

    @Test
    fun `an unknown sender writing a bank alert is held, not discarded`() {
        val result = parse(
            "AD-ZZBANK-S",
            "INR 2,340.00 debited from A/c XX9931 on 04AUG26 at BIGBAZAAR",
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Unrecognised::class.java)

        val held = result as SmsParser.Result.Unrecognised
        assertThat(held.header).isEqualTo("ZZBANK")
        assertThat(held.amountPaise).isEqualTo(2_34_000)
        assertThat(held.type).isEqualTo(TxnType.DEBIT)
    }

    /**
     * Refused outright, not queued: neither carries a settled verb, so neither is
     * payment-shaped at all. A queue of things that are not payments is one nobody empties.
     */
    @Test
    fun `the messages that only look like payments are still refused`() {
        val notPayments = listOf(
            "AD-IOCMKT-S" to
                "Thanks for filling 1.98 Ltrs Petrol for Rs. 210.00 @ IOCL Petrol Pump",
            "AD-SHOPPY-S" to "Your order of Rs.499 has been shipped and will arrive tomorrow.",
        )
        notPayments.forEach { (sender, body) ->
            assertThat(parse(sender, body))
                .isInstanceOf(SmsParser.Result.Ignored::class.java)
        }
    }

    /**
     * A gift card *is* payment-shaped, and gets asked about rather than assumed.
     *
     * Not a mistake: ₹340 did leave a Myntra balance. Whether the household wants that in
     * its spending is a question only the household can answer, and answering it once
     * settles every gift-card message thereafter.
     */
    @Test
    fun `a gift card payment is a question, because it is one`() {
        val result = parse(
            "AD-MYNTRA-S",
            "your payment of Rs. 340 using Myntra Gift Card ****1234 balance is successful",
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Unrecognised::class.java)
        // Whatever it is, it is not in any total until somebody says so.
        assertThat(result).isNotInstanceOf(SmsParser.Result.Expense::class.java)
    }

    /**
     * An OTP quoting a debit is bank-shaped by every measure this uses.
     *
     * The reject rules normally run *after* the sender gate, so without applying them in
     * the unrecognised branch too, every OTP from an unknown sender would queue up asking
     * whether its sender is a bank.
     */
    @Test
    fun `an OTP is never a question`() {
        val result = parse(
            "AD-NEWBNK-S",
            "123456 is your OTP for a debit of Rs.500 from A/c XX1111. Do not share.",
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
        assertThat((result as SmsParser.Result.Ignored).reason).isEqualTo(SmsParser.Reason.OTP)
    }

    @Test
    fun `a failed transaction is never a question`() {
        val result = parse(
            "AD-NEWBNK-S",
            "Rs.500 debited from A/c XX1111 has failed and been reversed.",
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
    }

    @Test
    fun `a personal phone number is never a question`() {
        val result = parse(
            "+919876543210",
            "Rs.500 debited from A/c XX1234 on 06AUG2026. Avl Bal Rs.900",
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
        assertThat((result as SmsParser.Result.Ignored).reason)
            .isEqualTo(SmsParser.Reason.UNKNOWN_SENDER)
    }

    // ─── What still goes straight through ────────────────────────────────────

    @Test
    fun `a known sender is never queued`() {
        val result = parse(
            "AX-KGBANK-S",
            "Rs.500.00 debited from A/c XX3062 on 05AUG2026 10:00:00. Avl Bal Rs.9,000",
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Expense::class.java)
    }

    /** A sender that names a bank we know identifies itself and skips the queue. */
    @Test
    fun `a message that names its bank is read, not queued`() {
        val result = parse(
            "AD-NEWFED-S",
            "Dear Customer, Rs.52 credited to your A/c XX4657 on 06AUG2026 18:38:28. " +
                "BAL-Rs.3000.23-Federal Bank",
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Expense::class.java)
        assertThat((result as SmsParser.Result.Expense).txn.bank).isEqualTo("Federal Bank")
    }

    /**
     * Answering teaches the sender, and the answer is what makes the next message an
     * expense instead of another question.
     */
    @Test
    fun `once taught, the same sender is read straight away`() {
        val body = "INR 2,340.00 debited from A/c XX9931 on 04AUG26 at BIGBAZAAR"
        assertThat(parse("AD-ZZBANK-S", body))
            .isInstanceOf(SmsParser.Result.Unrecognised::class.java)

        BankRules.setTaughtSenders(mapOf("ZZBANK" to "IDFC First"))

        val after = parse("AD-ZZBANK-S", body)
        assertThat(after).isInstanceOf(SmsParser.Result.Expense::class.java)
        assertThat((after as SmsParser.Result.Expense).txn.bank).isEqualTo("IDFC First")
    }
}
