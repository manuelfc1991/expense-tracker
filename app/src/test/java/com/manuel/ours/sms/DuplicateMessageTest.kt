package com.manuel.ours.sms

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.sms.SmsDeduplicator
import com.manuel.ours.data.sms.SmsParser
import org.junit.Test

/**
 * One debit, described twice by the same bank, is one row.
 *
 * Kerala Gramin sends **two** SMS for a single payment: a detailed one carrying a UPI
 * reference and a bare one carrying none. Nothing the deduplicator knew could tie them
 * together — the references cannot match when only one message has one, and the pair
 * arrives just over three minutes apart, a hair outside [SmsDeduplicator.WINDOW_MS].
 *
 * So both were stored. On this household's ledger that was a ₹1,778 card bill and a
 * ₹7,177.79 one, **₹8,955.79 counted twice in a single month** — enough to put the
 * household 153% of the way through a ₹38,500 budget that it had not actually spent.
 *
 * Both messages quote `Msg Id 2644123773`. That number is the bank telling us, plainly,
 * that this is one event, and it is now the first thing dedup looks at.
 */
class DuplicateMessageTest {

    private val parser = SmsParser()

    /** The two real messages, verbatim but for the account number. */
    private val withRef =
        "Your A/c XXXXXXXXXX3062 debited Rs.1778 for /  Bal after txn Rs 49244.9 " +
            "Msg Id 2644123773 UPI Ref 658119750447 Time 03-08-2026 19:49:05 -Kerala Grameena Bank"
    private val withoutRef =
        "Your A/c XXXXXXXXXX3062 debited Rs.1778 for /  Bal after txn Rs 49244.9 " +
            "Msg Id 2644123773 Time 03-08-2026 19:52:11 -Kerala Grameena Bank"

    private fun parse(body: String, at: Long) =
        (parser.parse("AX-KGBANK-S", body, at) as SmsParser.Result.Expense).txn

    // ─── The identity ────────────────────────────────────────────────────────

    @Test
    fun `the bank's message id is read`() {
        assertThat(parser.extractMessageId(withRef)).isEqualTo("2644123773")
        assertThat(parser.extractMessageId(withoutRef)).isEqualTo("2644123773")
    }

    /**
     * The message id must not become the reference shown on screen.
     *
     * `refNo` is what a person quotes at the bank. Folding the two together would have
     * been the cheaper fix and would have changed which number the detail screen shows.
     */
    @Test
    fun `it is kept separate from the reference`() {
        val detailed = parse(withRef, 0L)
        assertThat(detailed.refNo).isEqualTo("658119750447")
        assertThat(detailed.messageId).isEqualTo("2644123773")

        val bare = parse(withoutRef, 0L)
        assertThat(bare.refNo).isNull()
        assertThat(bare.messageId).isEqualTo("2644123773")
    }

    // ─── The defect ──────────────────────────────────────────────────────────

    @Test
    fun `the second message is recognised as the same payment`() {
        val first = parse(withRef, 0L)
        val second = parse(withoutRef, 0L)
        val stored = SmsDeduplicatorProbe.toEntity(first, lamport = 1)

        assertThat(SmsDeduplicator.isDuplicate(stored, second, stored.dedupeAt)).isTrue()
    }

    /**
     * And it must still be recognised across the gap that defeated every other rule:
     * no shared reference, and more than three minutes apart.
     */
    @Test
    fun `it matches even outside the time window and with no shared reference`() {
        val first = parse(withRef, 0L)
        val second = parse(withoutRef, 0L)
            .copy(dedupeAt = SmsDeduplicator.WINDOW_MS * 4)
        val stored = SmsDeduplicatorProbe.toEntity(first, lamport = 1)

        assertThat(second.refNo).isNull()
        assertThat(SmsDeduplicator.isDuplicate(stored, second, stored.dedupeAt)).isTrue()
    }

    // ─── What it must not do ─────────────────────────────────────────────────

    /**
     * Two rows that simply have no message id are not thereby the same payment.
     *
     * This is the failure mode of the obvious implementation: `existing.id == incoming.id`
     * is true when both are null, which would merge every unrelated pair the candidate
     * query returns — and that query is deliberately broad.
     */
    @Test
    fun `two rows without a message id are not merged`() {
        val a = parse("Rs.500 debited from a/c XX3062 on 03-08-26 to VPA shop@ybl", 1_000L)
        val b = parse("Rs.500 debited from a/c XX3062 on 03-08-26 to VPA other@ybl", 1_000L)
        assertThat(a.messageId).isNull()
        assertThat(b.messageId).isNull()

        val stored = SmsDeduplicatorProbe.toEntity(a, lamport = 1).copy(refNo = null)
        val incoming = b.copy(refNo = null, dedupeAt = SmsDeduplicator.WINDOW_MS * 10)
        assertThat(SmsDeduplicator.isDuplicate(stored, incoming, stored.dedupeAt)).isFalse()
    }

    /** Different ids are different payments, however alike they otherwise look. */
    @Test
    fun `different message ids are different payments`() {
        val first = parse(withRef, 0L)
        val second = parse(withoutRef.replace("2644123773", "2644999999"), 0L)
            .copy(dedupeAt = SmsDeduplicator.WINDOW_MS * 4)
        val stored = SmsDeduplicatorProbe.toEntity(first, lamport = 1)

        assertThat(SmsDeduplicator.isDuplicate(stored, second, stored.dedupeAt)).isFalse()
    }

    /** An amount that differs is never the same payment, whatever the id says. */
    @Test
    fun `a different amount is never the same payment`() {
        val first = parse(withRef, 0L)
        val second = parse(withoutRef.replace("Rs.1778", "Rs.1779"), 0L)
        val stored = SmsDeduplicatorProbe.toEntity(first, lamport = 1)

        assertThat(SmsDeduplicator.isDuplicate(stored, second, stored.dedupeAt)).isFalse()
    }
}
