package com.manuel.ours.data.sms

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Bank SMS dates come in a small but stubbornly inconsistent set of shapes.
 *
 * Every formatter is built `parseCaseInsensitive`. Banks are not consistent about
 * month casing even within one institution — Federal Bank sends both `02JUL2026` and
 * `01Jul26` — and `DateTimeFormatter` matches `MMM` case-sensitively by default.
 *
 * **The time matters as much as the date.** Keeping only the day collapses two
 * genuinely different same-day, same-amount transactions onto one timestamp, and the
 * deduplicator then throws the second one away. That is silent data loss, and it
 * happened on real data before [Parsed.hasTime] existed.
 */
object SmsDateParser {

    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    /**
     * @param hasTime false when the message gave a day but no clock time. Callers use
     *   this to fall back to the SMS delivery time for deduplication, which does have
     *   real resolution.
     */
    data class Parsed(val epochMillis: Long, val hasTime: Boolean)

    private fun formatter(pattern: String): DateTimeFormatter =
        DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern(pattern)
            .toFormatter(Locale.ENGLISH)

    /** Separator-bearing: 12-05-24, 19/Jul/2026, 02-JUL-26. */
    private val formats = listOf(
        "dd-MM-yy", "dd-MM-yyyy", "d-M-yy", "d-M-yyyy",
        "dd/MM/yy", "dd/MM/yyyy", "d/M/yy", "d/M/yyyy",
        "dd-MMM-yy", "dd-MMM-yyyy", "d-MMM-yy", "d-MMM-yyyy",
        "dd/MMM/yy", "dd/MMM/yyyy", "d/MMM/yy", "d/MMM/yyyy",
    ).map(::formatter)

    /** Federal Bank's separator-free form: 02JUL2026, 01Jul26, 4Jul26. */
    private val compactFormats = listOf(
        "ddMMMyyyy", "ddMMMyy", "dMMMyyyy", "dMMMyy",
    ).map(::formatter)

    /**
     * 24-hour first, then 12-hour with a meridiem.
     *
     * Kerala Gramin writes "10:48 PM"; Federal writes "22:57:32". Reading the first
     * as 10:48 was silent and doubly damaging: the row got the wrong time, and a
     * same-amount payment from that morning deduplicated against it and vanished.
     */
    private val timeFormats = listOf(
        "HH:mm:ss", "H:mm:ss", "HH:mm", "H:mm",
        "h:mm:ss a", "hh:mm:ss a", "h:mm a", "hh:mm a",
    ).map(::formatter)

    fun parse(date: String, time: String? = null): Parsed? = combine(date, time, formats)

    fun parseCompact(date: String, time: String? = null): Parsed? =
        combine(date, time, compactFormats)

    private fun combine(
        dateText: String,
        timeText: String?,
        patterns: List<DateTimeFormatter>,
    ): Parsed? {
        val date = tryDate(dateText.trim(), patterns) ?: return null
        val time = timeText?.trim()?.takeIf { it.isNotEmpty() }?.let(::tryTime)

        val instant = if (time != null) {
            LocalDateTime.of(date, time).atZone(zone).toInstant()
        } else {
            date.atStartOfDay(zone).toInstant()
        }
        return Parsed(instant.toEpochMilli(), hasTime = time != null)
    }

    private fun tryDate(text: String, patterns: List<DateTimeFormatter>): LocalDate? {
        if (text.isEmpty()) return null
        for (fmt in patterns) {
            try {
                return LocalDate.parse(text, fmt)
            } catch (_: DateTimeParseException) {
                // try the next shape
            }
        }
        return null
    }

    private fun tryTime(raw: String): LocalTime? {
        // "10:48 p.m." and "10:48PM" are the same instant as "10:48 PM"; only the
        // last shape parses, so normalise before trying.
        val text = raw.trim()
            .replace(".", "")
            .replace(Regex("\\s*([AaPp][Mm])$"), " $1")
            .uppercase()
        for (fmt in timeFormats) {
            try {
                return LocalTime.parse(text, fmt)
            } catch (_: DateTimeParseException) {
                // try the next shape
            }
        }
        return null
    }
}
