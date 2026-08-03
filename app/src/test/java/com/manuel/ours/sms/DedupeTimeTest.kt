package com.manuel.ours.sms

import com.manuel.ours.data.sms.SmsDeduplicator
import com.manuel.ours.data.sms.SmsParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Guards against silent data loss in deduplication.
 *
 * This suite exists because two real transactions were quietly dropped: the compact
 * date parser kept the day but discarded the clock time, so every Federal Bank
 * message that day carried the identical timestamp, and the deduplicator concluded
 * two genuinely different payments were one.
 *
 * Losing a transaction is far worse than keeping a duplicate. A duplicate is visible
 * and one tap deletes it; a missing transaction is invisible forever.
 */
class DedupeTimeTest {

    private val parser = SmsParser()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val delivered = 1_785_000_000_000L

    private fun expense(body: String, receivedAt: Long = delivered) =
        (parser.parse("AD-FEDBNK", body, receivedAt) as SmsParser.Result.Expense).txn

    private fun hourOf(epochMillis: Long) =
        Instant.ofEpochMilli(epochMillis).atZone(zone).hour

    @Test
    fun `the clock time in the message is kept, not thrown away`() {
        val t = expense(
            "Debited Rs 10000 from a/c XX4657 on 02JUL2026 07:20:07.Bal Rs 13572.55." +
                "Not you?Call 18004251199 -Federal Bank"
        )
        assertThat(hourOf(t.occurredAt)).isEqualTo(7)
    }

    @Test
    fun `HH mm without seconds is kept too`() {
        val t = expense(
            "Debited Rs 151.00 from a/c X4657 on 01Jul26 07:48 via UPI to KEECHERIL ST. " +
                "Ref 618233824289.Bal Rs 3469.55. -Federal Bank"
        )
        assertThat(hourOf(t.occurredAt)).isEqualTo(7)
    }

    @Test
    fun `two different payments on the same day are not merged`() {
        // The exact shape that lost data: same amount, same day, same account,
        // minutes apart, neither carrying a reference number.
        val morning = expense(
            "Debited Rs 10000 from a/c XX4657 on 02JUL2026 07:20:07.Bal Rs 13572.55." +
                "-Federal Bank"
        )
        val night = expense(
            "Debited Rs 10000 from a/c XX4657 on 02JUL2026 22:57:32.Bal Rs 3572.55." +
                "-Federal Bank"
        )

        val morningKey = SmsDeduplicator.bucketKey(
            morning.amountPaise, morning.dedupeAt, morning.refNo,
        )
        val nightKey = SmsDeduplicator.bucketKey(
            night.amountPaise, night.dedupeAt, night.refNo,
        )
        assertThat(morningKey).isNotEqualTo(nightKey)
        assertThat(SmsDeduplicator.candidateKeys(night.amountPaise, night.dedupeAt, night.refNo))
            .doesNotContain(morningKey)
    }

    @Test
    fun `two one rupee credits minutes apart both survive`() {
        // Both of these are real, and the second used to vanish.
        val first = expense(
            "Rs 1 credited to your A/c XX4657 via NEFT from INDIAN CLE on 19FEB2026 " +
                "19:39:09 Ref No HDFCH00814332434 Bal:Rs 50760.50 -Federal Bank"
        )
        val second = expense(
            "Rs 1 credited to your A/c XX4657 via NEFT from INDIAN CLE on 19FEB2026 " +
                "19:44:36 Ref No HDFCH00814336404 Bal:Rs 50761.50 -Federal Bank"
        )
        // 5 minutes apart, beyond the 3-minute window.
        assertThat(SmsDeduplicator.bucketKey(first.amountPaise, first.dedupeAt, first.refNo))
            .isNotEqualTo(
                SmsDeduplicator.bucketKey(second.amountPaise, second.dedupeAt, second.refNo)
            )
    }

    @Test
    fun `a date without a time falls back to the delivery time for deduping`() {
        // ICICI card alerts name a date but no clock time.
        val body = "INR 349.00 spent using ICICI Bank Card XX3008 on 17-Jul-26 on " +
            "AMAZON PAY IN R. Avl Limit: INR 7,733.22."
        val a = (parser.parse("VM-ICICIB", body, 1_785_000_000_000L)
            as SmsParser.Result.Expense).txn
        val b = (parser.parse("VM-ICICIB", body, 1_785_000_600_000L) // 10 minutes later
            as SmsParser.Result.Expense).txn

        // Same displayed day…
        assertThat(a.occurredAt).isEqualTo(b.occurredAt)
        // …but dedup can still tell two separate purchases apart.
        assertThat(a.dedupeAt).isNotEqualTo(b.dedupeAt)
        assertThat(SmsDeduplicator.bucketKey(a.amountPaise, a.dedupeAt, a.refNo))
            .isNotEqualTo(SmsDeduplicator.bucketKey(b.amountPaise, b.dedupeAt, b.refNo))
    }

    @Test
    fun `the same message parsed twice always dedupes to the same key`() {
        // Rescanning the inbox must never create duplicates.
        val body = "Debited Rs 210.00 from a/c X4657 on 04Jul26 16:53 via UPI to " +
            "RAMSON NADAR. Ref 618587933197.Bal Rs 3142.55. -Federal Bank"
        val first = expense(body, receivedAt = delivered)
        val second = expense(body, receivedAt = delivered + 86_400_000) // rescanned a day later

        assertThat(SmsDeduplicator.bucketKey(first.amountPaise, first.dedupeAt, first.refNo))
            .isEqualTo(
                SmsDeduplicator.bucketKey(second.amountPaise, second.dedupeAt, second.refNo)
            )
    }

    @Test
    fun `the genuine bank plus UPI-app pair is still collapsed`() {
        // The real duplicate case must keep working: one payment, two senders,
        // seconds apart, sharing a UPI reference.
        val bankMsg = expense(
            "Debited Rs 450.00 from a/c X4657 on 04Jul26 16:53 via UPI to SWIGGY. " +
                "Ref 618587933197.Bal Rs 3142.55. -Federal Bank"
        )
        val appMsg = (parser.parse(
            "JD-PHONPE",
            "You paid Rs.450 to SWIGGY via PhonePe UPI Ref 618587933197",
            delivered + 20_000,
        ) as SmsParser.Result.Expense).txn

        assertThat(SmsDeduplicator.candidateKeys(appMsg.amountPaise, appMsg.dedupeAt, appMsg.refNo))
            .contains(SmsDeduplicator.bucketKey(bankMsg.amountPaise, bankMsg.dedupeAt, bankMsg.refNo))
    }
}
