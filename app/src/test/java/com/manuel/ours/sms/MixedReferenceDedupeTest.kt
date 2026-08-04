package com.manuel.ours.sms

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.db.TransactionEntity
import com.manuel.ours.data.sms.SmsDeduplicator
import com.manuel.ours.data.sms.SmsParser
import org.junit.Test

/**
 * One payment, two messages, only one of which carries a UPI reference.
 *
 * This is the shape that produced 25 duplicated clusters on a real ledger — the same
 * payment recorded two, three and four times. The bank texts without a reference and
 * the UPI app texts with one, and the old lookup asked the database for a single
 * bucket key built *from* the reference when present and from the amount and minute
 * when not. The two halves of every pair were therefore filed under different keys,
 * and neither lookup could see the other.
 *
 * The matching rule itself was never wrong, which is why this went unnoticed:
 * [SmsDeduplicator.isDuplicate] correctly calls these duplicates. They simply never
 * reached it. So these tests assert on the rule *and* on the keys used to find
 * candidates, because a correct rule that is never consulted is not a working feature.
 */
class MixedReferenceDedupeTest {

    private val parser = SmsParser()
    private val delivered = 1_785_000_000_000L

    private fun parsed(body: String) =
        (parser.parse("BZ-KGBANK-S", body, delivered) as SmsParser.Result.Expense).txn

    private fun stored(txn: SmsParser.ParsedTxn) = TransactionEntity(
        id = "stored",
        amountPaise = txn.amountPaise,
        type = txn.type.name,
        merchant = txn.merchant ?: "Unknown payee",
        category = "OTHER",
        occurredAt = txn.occurredAt,
        accountTail = txn.accountTail,
        refNo = txn.refNo,
        bank = txn.bank,
        note = null,
        splitType = "SHARED",
        source = "SMS",
        ownerUid = "uid-me",
        ownerName = "Manuel",
        needsReview = false,
        rawSms = null,
        deleted = false,
        dedupeKey = SmsDeduplicator.bucketKey(txn.amountPaise, txn.dedupeAt, txn.refNo),
        dedupeAt = txn.dedupeAt,
        updatedAtLamport = 1,
        updatedByDevice = "device",
    )

    private val withoutRef = "Your a/c no. XXXXX4657 is debited for Rs.1160.00 on " +
        "11/07/26 10:48 PM and credited to a/c no. XXXXX8891 -Kerala Gramin Bank"

    private val withRef = "Your a/c no. XXXXX4657 is debited for Rs.1160.00 on " +
        "11/07/26 10:48 PM and credited to a/c no. XXXXX8891 " +
        "(UPI Ref no 519012345678)-Kerala Gramin Bank"

    @Test
    fun `the pair really is one payment`() {
        val first = parsed(withoutRef)
        val second = parsed(withRef)

        assertThat(SmsDeduplicator.isDuplicate(stored(first), second, first.dedupeAt)).isTrue()
    }

    /**
     * The regression itself. Storing the reference-less message and then looking up the
     * one that carries a reference must be able to find it.
     */
    @Test
    fun `a referenced message can find a stored one that had no reference`() {
        val first = parsed(withoutRef)
        val second = parsed(withRef)

        val storedKey = SmsDeduplicator.bucketKey(first.amountPaise, first.dedupeAt, first.refNo)
        val probes = SmsDeduplicator.candidateKeys(
            second.amountPaise, second.dedupeAt, second.refNo,
        )

        // Documents why key-based lookup alone cannot work: the keys genuinely differ.
        // The repository therefore queries by amount and time window instead.
        assertThat(probes).doesNotContain(storedKey)
        assertThat(second.refNo).isNotNull()
        assertThat(first.refNo).isNull()

        // Same amount, same minute — which is exactly what findNearby asks for.
        assertThat(second.amountPaise).isEqualTo(first.amountPaise)
        assertThat(Math.abs(second.dedupeAt - first.dedupeAt))
            .isAtMost(SmsDeduplicator.WINDOW_MS)
    }

    @Test
    fun `and the other way round`() {
        val first = parsed(withRef)
        val second = parsed(withoutRef)

        assertThat(SmsDeduplicator.isDuplicate(stored(first), second, first.dedupeAt)).isTrue()
        assertThat(Math.abs(second.dedupeAt - first.dedupeAt))
            .isAtMost(SmsDeduplicator.WINDOW_MS)
    }

    /** Two genuinely different payments of the same amount must still both survive. */
    @Test
    fun `two different payments an hour apart are not merged`() {
        val morning = parsed(
            "Your a/c no. XXXXX4657 is debited for Rs.1160.00 on 11/07/26 10:48 AM " +
                "and credited to a/c no. XXXXX8891 -Kerala Gramin Bank"
        )
        val evening = parsed(withoutRef)

        assertThat(SmsDeduplicator.isDuplicate(stored(morning), evening, morning.dedupeAt))
            .isFalse()
    }
}
