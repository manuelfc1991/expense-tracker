package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.sms.SmsDeduplicator
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.sms.SmsDeduplicatorProbe
import org.junit.Test

/**
 * Re-reading the inbox must not overturn a deletion.
 *
 * The three dedup lookups all filtered `deleted = 0`, so a tombstone was invisible to them.
 * A rescan met a message it had already imported, found nothing to match it against, and
 * stored it again as new. On 6 August this household deleted six rows to clean up a month;
 * the next rescan brought all six back and put **₹50,955 of duplicates** into it, taking a
 * ₹29,260 month to ₹80,215 in one tap.
 *
 * A deleted row is still proof the message was seen. What it means is not "import this
 * again" — it is "we have met this one and the household said no".
 */
class RescanRespectsDeletionTest {

    private val parser = SmsParser()

    private val message =
        "Your A/c XXXXXXXXXX3062 debited Rs.20000 for /  Bal after txn Rs 3357.35 " +
            "Msg Id 2644987654 Time 03-08-2026 21:03:55 -Kerala Grameena Bank"

    private fun parsed() =
        (parser.parse("AX-KGBANK-S", message, 1_000L) as SmsParser.Result.Expense).txn

    /**
     * The rule the deduplicator itself enforces: same message, same payment, whatever the
     * row's state. `isDuplicate` never looked at `deleted` and does not need to — the bug
     * was that the query never handed it the deleted row in the first place.
     */
    @Test
    fun `a deleted row still matches the message it came from`() {
        val incoming = parsed()
        val deletedRow = SmsDeduplicatorProbe.toEntity(incoming, lamport = 1)
            .copy(deleted = true, deletedAt = 2_000L)

        assertThat(SmsDeduplicator.isDuplicate(deletedRow, incoming, deletedRow.dedupeAt))
            .isTrue()
    }

    /**
     * And it matches on the bank's own message id, which is what a Kerala Gramin rescan
     * actually has to go on — these messages carry no UPI reference.
     */
    @Test
    fun `it matches a deleted row by message id alone`() {
        val incoming = parsed()
        assertThat(incoming.messageId).isEqualTo("2644987654")

        val deletedRow = SmsDeduplicatorProbe.toEntity(incoming, lamport = 1)
            .copy(
                deleted = true,
                deletedAt = 2_000L,
                refNo = null,
                // Far outside any window, which is exactly where a rescan lands.
                dedupeAt = incoming.dedupeAt - SmsDeduplicator.WINDOW_MS * 100,
            )

        assertThat(SmsDeduplicator.isDuplicate(deletedRow, incoming, deletedRow.dedupeAt))
            .isTrue()
    }

    /** Two genuinely different payments are still two, deleted or not. */
    @Test
    fun `a deleted row does not swallow an unrelated payment`() {
        val incoming = parsed()
        val other = (
            parser.parse(
                "AX-KGBANK-S",
                message.replace("Rs.20000", "Rs.21000").replace("2644987654", "2644111111"),
                1_000L,
            ) as SmsParser.Result.Expense
            ).txn
        val deletedRow = SmsDeduplicatorProbe.toEntity(incoming, lamport = 1)
            .copy(deleted = true, deletedAt = 2_000L)

        assertThat(SmsDeduplicator.isDuplicate(deletedRow, other, deletedRow.dedupeAt))
            .isFalse()
    }
}
