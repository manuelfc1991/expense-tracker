package com.manuel.ours.sms

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.sms.BankRules
import com.manuel.ours.data.sms.SmsParser
import org.junit.Before
import org.junit.Test

/**
 * Reading a payment from any sender at all, because the household asked for it.
 *
 * The trade was measured before it was offered. Over 2,810 of this household's messages,
 * 99 unrecognised headers write something payment-shaped and **none of the six that do is a
 * bank** — an EPF passbook line, an Amazon Pay balance, a fuel receipt, a gift card and two
 * trading spams. With this on they become rows and count until removed, and the EPF one
 * reads as ₹61,989 of income.
 *
 * The household would rather delete six rows than miss a bank, which is a fair call to make
 * about one's own ledger: a missed bank is silent and costs months, a wrong row is visible
 * and costs a tap. What the code owes them is that the wrong rows are **easy to find** —
 * hence [SmsParser.ParsedTxn.senderVouched], which puts every one of them under Untagged.
 */
class ReadEveryPaymentTest {

    private val parser = SmsParser()

    @Before
    fun reset() {
        BankRules.setTaughtSenders(emptyMap())
        BankRules.forgetDiscovered()
    }

    private val debit = "INR 2,340.00 debited from A/c XX9931 on 04AUG26 at BIGBAZAAR"

    private fun parse(sender: String, body: String, on: Boolean) =
        parser.parse(sender, body, 1_000L, readEveryPayment = on)

    // ─── On ──────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown sender is read, and the header stands in for the bank`() {
        val result = parse("AD-ZZBANK-S", debit, on = true)
        assertThat(result).isInstanceOf(SmsParser.Result.Expense::class.java)

        val txn = (result as SmsParser.Result.Expense).txn
        assertThat(txn.amountPaise).isEqualTo(2_34_000)
        // All we know about who sent it is the six letters it came from, and saying so is
        // honest. Inventing a bank name would not be.
        assertThat(txn.bank).isEqualTo("ZZBANK")
    }

    /**
     * The flag that makes the household's plan workable.
     *
     * Their answer to a wrong row is "I will delete it", and that only holds if the wrong
     * rows can be found together. Unvouched rows are flagged, which puts them under
     * Untagged rather than scattered through the month.
     */
    @Test
    fun `a row read this way is flagged for review`() {
        val txn = (parse("AD-ZZBANK-S", debit, on = true) as SmsParser.Result.Expense).txn
        assertThat(txn.senderVouched).isFalse()
    }

    @Test
    fun `a known bank is still vouched for, and not flagged`() {
        val txn = (
            parse(
                "AX-KGBANK-S",
                "Rs.500.00 debited from A/c XX3062 on 05AUG2026 10:00:00. Avl Bal Rs.9,000",
                on = true,
            ) as SmsParser.Result.Expense
            ).txn
        assertThat(txn.senderVouched).isTrue()
    }

    /** A message that names its bank is vouched for by the message, not by the header. */
    @Test
    fun `naming the bank counts as vouching`() {
        val txn = (
            parse(
                "AD-NEWFED-S",
                "Rs.52 credited to your A/c XX4657 on 06AUG2026 18:38:28. " +
                    "BAL-Rs.3000.23-Federal Bank",
                on = true,
            ) as SmsParser.Result.Expense
            ).txn
        assertThat(txn.senderVouched).isTrue()
        assertThat(txn.bank).isEqualTo("Federal Bank")
    }

    /**
     * A guess is never promoted to knowledge.
     *
     * Remembering a header adopted on shape alone would let its next message through
     * *unflagged*, quietly undoing the one protection this mode has.
     */
    @Test
    fun `a sender adopted on shape alone is not learned`() {
        parse("AD-ZZBANK-S", debit, on = true)
        assertThat(BankRules.forSender("AD-ZZBANK-S")).isNull()

        val again = parse("AD-ZZBANK-S", debit, on = true) as SmsParser.Result.Expense
        assertThat(again.txn.senderVouched).isFalse()
    }

    // ─── What it still refuses ───────────────────────────────────────────────

    /** The reject rules run first and are not weakened by this. */
    @Test
    fun `an OTP is not a payment however wide the net`() {
        val result = parse(
            "AD-ZZBANK-S",
            "123456 is your OTP for a debit of Rs.500 from A/c XX1111. Do not share.",
            on = true,
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
    }

    @Test
    fun `a personal phone number is never read`() {
        val result = parse("+919876543210", debit, on = true)
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
    }

    @Test
    fun `a message with no settled verb is still not a payment`() {
        val result = parse(
            "AD-SHOPPY-S",
            "Your order of Rs.499 has been shipped and will arrive tomorrow.",
            on = true,
        )
        assertThat(result).isInstanceOf(SmsParser.Result.Ignored::class.java)
    }

    // ─── Off ─────────────────────────────────────────────────────────────────

    @Test
    fun `switched off, the same message is held instead of read`() {
        val result = parse("AD-ZZBANK-S", debit, on = false)
        assertThat(result).isInstanceOf(SmsParser.Result.Unrecognised::class.java)
        assertThat(result).isNotInstanceOf(SmsParser.Result.Expense::class.java)
    }

    /** The default is off at the parser, so nothing changes for a caller that never asks. */
    @Test
    fun `the parser does not widen the net unless asked`() {
        assertThat(parser.parse("AD-ZZBANK-S", debit, 1_000L))
            .isInstanceOf(SmsParser.Result.Unrecognised::class.java)
    }
}
