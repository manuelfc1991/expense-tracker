package com.manuel.ours.domain

import java.util.concurrent.TimeUnit

/**
 * How long a deleted entry stays recoverable.
 *
 * ## Why the tombstone is not destroyed at the end of it
 *
 * "Kept for 30 days" is about what a person can get back, and that is what this window
 * governs. It is deliberately **not** a purge: after 30 days the entry leaves Trash and
 * stops being restorable, but the row stays in the table with `deleted = 1`.
 *
 * Hard-deleting it would be the tidier-looking choice and a genuinely dangerous one. A
 * deletion is carried between phones as a DELETE event, and event compaction eventually
 * drops pushed events; if the tombstone row were gone too, nothing on this phone would
 * remember the deletion. A peer that had been offline since before it — or a re-upload
 * replaying an older UPSERT for the same id — would then be merged as a row this device
 * has never seen, and the entry would come back from the dead, months later, with no
 * explanation. Losing a transaction is worse than keeping a duplicate; silently
 * resurrecting one is worse than both.
 *
 * The cost of keeping them is a few hundred bytes each. The first real household had 446
 * tombstones and they account for well under a megabyte.
 */
object Trash {

    const val WINDOW_DAYS = 30L

    private val WINDOW_MS = TimeUnit.DAYS.toMillis(WINDOW_DAYS)

    /** The oldest `deletedAt` still inside the window at [now]. */
    fun since(now: Long): Long = now - WINDOW_MS

    /**
     * Whole days left before an entry drops out, floored at zero.
     *
     * Rounded **up**, so an entry deleted a few minutes ago reads "30 days left" rather
     * than "29". The number is a promise about when something disappears, and a promise
     * that rounds against the reader is the wrong way round.
     */
    fun daysLeft(deletedAt: Long, now: Long): Int {
        val elapsed = now - deletedAt
        if (elapsed >= WINDOW_MS) return 0
        val remaining = WINDOW_MS - elapsed
        return Math.ceil(remaining.toDouble() / TimeUnit.DAYS.toMillis(1)).toInt()
    }

    /** "30 days left" / "1 day left" / "Gone today" — the caption under a trashed row. */
    fun expiryLabel(deletedAt: Long, now: Long): String = when (val d = daysLeft(deletedAt, now)) {
        0 -> "Gone today"
        1 -> "1 day left"
        else -> "$d days left"
    }
}
