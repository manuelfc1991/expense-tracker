package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

/**
 * A row must not print a clock time the bank never gave it.
 *
 * `SmsDateParser` knows exactly whether a message carried a time — `Parsed.hasTime` — but that
 * flag is consumed by dedup and never stored, so a screen has only the instant to go on. When
 * the bank gave a date alone the parser stores local midnight, and the statement row rendered
 * that as "12:00 AM": a card bill dated 3 August read as though it had been paid at midnight.
 *
 * The app should not claim precision it was never given, so midnight is treated as "no time"
 * and the row shows its category alone.
 */
class DateOnlyTest {

    @Test
    fun `local midnight is treated as a date without a time`() {
        val midnight = OursZone.startOfDay(LocalDate.of(2026, 8, 3))
        assertThat(OursZone.isDateOnly(midnight)).isTrue()
    }

    @Test
    fun `any real clock time is not`() {
        val base = OursZone.startOfDay(LocalDate.of(2026, 8, 3))
        // 19:49:05 — the timestamp on this household's Kerala Gramin card-bill message.
        assertThat(OursZone.isDateOnly(base + (19 * 3600 + 49 * 60 + 5) * 1000L)).isFalse()
        // And one millisecond past midnight is a time, however unhelpful.
        assertThat(OursZone.isDateOnly(base + 1)).isFalse()
    }

    /**
     * Midnight is the household's, not the device's.
     *
     * The zone is fixed to Asia/Kolkata, so a phone that has travelled must not start deciding
     * that a 00:00 IST transaction happened at 18:30 the day before and had a time after all.
     */
    @Test
    fun `midnight is measured in the household's zone`() {
        val midnightIst = OursZone.startOfDay(LocalDate.of(2026, 2, 15))
        assertThat(OursZone.isDateOnly(midnightIst)).isTrue()
        // UTC midnight of the same day is 05:30 in Kolkata — a time, not a bare date.
        val midnightUtc = LocalDate.of(2026, 2, 15)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertThat(OursZone.isDateOnly(midnightUtc)).isFalse()
    }
}
