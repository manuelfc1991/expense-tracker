package com.manuel.ours.core

import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The one timezone, and the formatters that go with it.
 *
 * ## Why this exists
 *
 * `docs/REVIEW.md` §3: all money maths ran in a fixed zone — `MonthlyAggregator.ZONE` —
 * and **every date the interface formatted ran in another**, `ZoneId.systemDefault()`. Nine
 * places, including the tracking-start date picker, which converted the chosen day locally and
 * then compared it against transactions bucketed into months in IST.
 *
 * Both phones sit in IST, so the two agreed and nothing was visibly wrong. Take one phone to
 * another zone and the cutoff moves by hours, and Activity's day headings stop agreeing with the
 * month totals they sit under.
 *
 * That last part is why this is a design rule and not only an implementation detail: **a day's
 * subtotal has to equal the sum of the rows printed under it.** Two zones cannot guarantee that.
 *
 * ## Why a fixed zone rather than the device's
 *
 * A household's month is a fact about the household, not about where somebody happens to be
 * standing. If one partner travels, August must not become a different set of transactions on
 * their phone than on the other — the two devices reconcile against one shared ledger, and a
 * month boundary that moved per device would make the same sheet disagree with itself.
 *
 * `ExportManager`, `BackupManager` and `SmsDateParser` already hard-coded Asia/Kolkata and were
 * consistent; the interface was the odd one out.
 */
object OursZone {

    /**
     * Asia/Kolkata. Not the device zone — see above.
     *
     * If this app is ever used by a household that does not live in India, this is the single
     * value to change, and it should become a household setting synced like any other shared
     * rule rather than a per-device default.
     */
    val ID: ZoneId = ZoneId.of("Asia/Kolkata")

    /**
     * Formatters, allocated once.
     *
     * `DateTimeFormatter.ofPattern` parses its pattern string and builds a formatter, which is
     * not free and was happening **per row, per recomposition** in the statement list — the
     * hottest path in the app, over a list that can hold several hundred entries. Hoisting them
     * here costs one allocation for the process.
     *
     * `DateTimeFormatter` is immutable and thread-safe, so sharing them is safe.
     */

    /** "7:48 am" — the clock on today's rows. */
    val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())

    /** "4 Aug" — the day, for a row not filed under a date rule. */
    val day: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    /** "4 Aug 2026" — a settings value, a backup stamp. */
    val date: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

    /** "4 Aug 2026 · 7:12 pm" — the detail screen, where you reconcile against a statement. */
    val dateTime: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", Locale.getDefault())

    /** "4 Aug 2026, 7:12 pm" — the restore dialog. */
    val dateTimeComma: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.getDefault())

    /** "August 2026" — the month stepper. */
    val month: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    /** "Wed, 5 Aug" — a date rule in the statement. */
    val dayRule: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())

    /** Format an epoch-millis instant in the household's zone. */
    fun format(epochMillis: Long, formatter: DateTimeFormatter): String =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(ID).format(formatter)

    /** The household's today, which is not necessarily the device's. */
    fun today(): java.time.LocalDate = java.time.LocalDate.now(ID)

    /** Local midnight of a chosen day, for a cutoff that must line up with month buckets. */
    fun startOfDay(date: java.time.LocalDate): Long =
        date.atStartOfDay(ID).toInstant().toEpochMilli()

    /** The day an instant fell on, in the household's zone. */
    fun dateOf(epochMillis: Long): java.time.LocalDate =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(ID).toLocalDate()

    /**
     * True when the bank gave a date but no clock time.
     *
     * `SmsDateParser` knows this exactly — [SmsDateParser.Parsed.hasTime] — but that flag is
     * consumed by dedup and never stored, so by the time a row reaches a screen the only
     * evidence left is that its instant is *exactly* local midnight. A real payment lands on
     * that millisecond about once in 86 million, and the cost of being wrong is one row
     * showing its day instead of "12:00 am", so the inference is worth making.
     *
     * It matters because the alternative is a lie: a card bill that the bank dated and did not
     * time was rendering as "12:00 AM", which reads as a payment made at midnight. The app
     * should not claim a precision it was never given.
     */
    fun isDateOnly(epochMillis: Long): Boolean =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(ID).toLocalTime() ==
            java.time.LocalTime.MIDNIGHT
}
