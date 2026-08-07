package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.db.toDomain
import com.manuel.ours.data.db.toEntity
import com.manuel.ours.data.sms.BankRules
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import com.manuel.ours.sms.SmsDeduplicatorProbe
import org.junit.Test

/**
 * Reproductions for a QA pass. Each test states a claim and proves it or disproves it.
 *
 * Written before any fix, deliberately: a bug that cannot be reproduced cannot be shown to
 * be fixed, and three of the claims below turned out to need correcting once measured.
 */
class BugReproTest {

    private val parser = SmsParser()

    // ── A · do the entity mappers carry the refund columns? ──────────────────

    @Test
    fun `A refund columns survive a round trip through the mappers`() {
        val txn = Transaction(
            id = "t1",
            amountPaise = 200_000,
            type = TxnType.DEBIT,
            merchant = "Amazon",
            category = Category.SHOPPING,
            occurredAt = 1_000L,
            ownerUid = "me",
            ownerName = "Manuel",
            splitType = SplitType.SHARED,
            source = TxnSource.SMS,
            refundedPaise = 200_000,
        )
        val back = txn.toEntity("k", 1L, "dev").toDomain()
        assertThat(back.refundedPaise).isEqualTo(200_000)

        val credit = txn.copy(id = "c1", type = TxnType.CREDIT, refundsTxnId = "t1")
        assertThat(credit.toEntity("k", 1L, "dev").toDomain().refundsTxnId).isEqualTo("t1")
    }

    // ── B · can a parsed amount overflow into a negative? ────────────────────

    @Test
    fun `B a huge amount never becomes negative or absurd`() {
        // BigDecimal.toLong() truncates high bits rather than throwing, so the claim is
        // that this wraps. Anything negative is unambiguously a bug.
        val paise = Money.parseToPaise("92233720368547758.08")
        assertThat(paise == null || paise >= 0).isTrue()

        val huge = Money.parseToPaise("99999999999999999999")
        assertThat(huge == null || huge >= 0).isTrue()
    }

    @Test
    fun `B2 an overflowing amount in a real SMS does not become a transaction`() {
        val result = parser.parse(
            "AD-HDFCBK",
            "INR 92233720368547758.08 debited from a/c XX1234 ref no 123456",
            1_000L,
        )
        val amount = (result as? SmsParser.Result.Expense)?.txn?.amountPaise
        assertThat(amount == null || amount >= 0).isTrue()
    }

    // ── C · can a crafted body make the parser hang? ─────────────────────────

    @Test(timeout = 10_000)
    fun `C a long run of masking characters does not hang the parser`() {
        // Runs on every message from an unknown sender, before any trust decision, and
        // the backfill does it for the whole inbox. A body like this arrives by SMS.
        val body = "Your a/c " + "X".repeat(3_000) + " has been debited"
        parser.parse("AD-SPAMMY-S", body, 1_000L, readEveryPayment = true)
    }

    @Test(timeout = 10_000)
    fun `C2 a long run of spaces after a keyword does not hang the parser`() {
        parser.parse("AD-SPAMMY-S", "ref" + " ".repeat(3_000) + "1", 1_000L, true)
        parser.parse("AD-SPAMMY-S", "bal" + " ".repeat(3_000) + "1", 1_000L, true)
    }

    // ── D · can an authorisation code become an expense? ─────────────────────

    @Test
    fun `D a PIN or authorisation code is never an expense`() {
        val shouldAllBeRejected = listOf(
            "123456 is your PIN for the transaction of Rs 4,821.00 at AMAZON",
            "Enter code 458213 to authorise a purchase of INR 4,821 at AMAZON",
        )
        shouldAllBeRejected.forEach { body ->
            val result = parser.parse("AD-HDFCBK", body, 1_000L)
            assertThat(result).isNotInstanceOf(SmsParser.Result.Expense::class.java)
        }
    }

    // ── F · does a one-letter sender get vouched for as a bank? ──────────────

    @Test
    fun `F a one or two character sender is not matched to a bank by prefix`() {
        // "YESBNK".startsWith("Y") must not make AD-Y into Yes Bank.
        assertThat(BankRules.forSender("AD-Y")).isNull()
        assertThat(BankRules.forSender("AD-AU")).isNull()
    }

    /** A real short header that genuinely is the bank must still resolve. */
    @Test
    fun `F2 a real short header still resolves`() {
        assertThat(BankRules.forSender("AD-SBI-S")?.bank).isEqualTo("State Bank of India")
        assertThat(BankRules.forSender("VM-GPAY")?.bank).isEqualTo("Google Pay")
    }

    // ── E · does the amount regex match "rs" inside a word? ──────────────────

    @Test
    fun `E the currency marker is not matched inside an ordinary word`() {
        // "Yours 24x7" must not yield ₹24, and "Customers 50000" must not yield ₹50,000.
        assertThat(parser.extractAmount("Yours 24x7 ICICI Bank")).isNull()
        assertThat(parser.extractAmount("Dear Customers 50000 is credited")).isNull()
    }

    /** The real thing must still parse, including the abbreviation with no full stop. */
    @Test
    fun `E2 real currency markers still parse`() {
        assertThat(parser.extractAmount("Rs.450.75 debited")).isEqualTo(45_075)
        assertThat(parser.extractAmount("INR 2,340.00 debited")).isEqualTo(2_34_000)
        assertThat(parser.extractAmount("₹899 debited")).isEqualTo(89_900)
        assertThat(parser.extractAmount("Rs 5000 withdrawn")).isEqualTo(5_00_000)
    }

    // ── G · does a deleted row still block a re-import? (regression) ─────────

    @Test
    fun `G a deleted row is still recognised as the same payment`() {
        val txn = (
            parser.parse(
                "AX-KGBANK-S",
                "Your A/c XXXXXXXXXX3062 debited Rs.1778 for /  Bal after txn Rs 49244.9 " +
                    "Msg Id 2644123773 Time 03-08-2026 19:49:05 -Kerala Grameena Bank",
                1_000L,
            ) as SmsParser.Result.Expense
            ).txn
        val deleted = SmsDeduplicatorProbe.toEntity(txn, 1L).copy(deleted = true)
        assertThat(
            com.manuel.ours.data.sms.SmsDeduplicator.isDuplicate(deleted, txn, deleted.dedupeAt)
        ).isTrue()
    }
}
