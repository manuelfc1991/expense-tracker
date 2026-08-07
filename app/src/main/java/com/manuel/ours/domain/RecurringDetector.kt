package com.manuel.ours.domain

import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import kotlin.math.abs

/**
 * A charge the household has committed to, inferred from what has already happened.
 *
 * Nothing declares a subscription — no bank SMS says "this is a subscription". So the
 * only honest source is the pattern: the same payee, for about the same amount, at about
 * the same interval, enough times that it is not a coincidence.
 */
data class RecurringCharge(
    val merchant: String,
    val category: Category,
    /** The median, not the mean — one annual price rise should not drag the figure. */
    val typicalPaise: Long,
    val cadence: Cadence,
    val occurrences: Int,
    val lastSeenAt: Long,
    val nextExpectedAt: Long,
) {
    /**
     * What this costs per month, so cadences can be summed against one another.
     *
     * A ₹1,200 quarterly charge and a ₹400 monthly one are the same commitment, and a
     * total that added 1200 to 400 would say otherwise.
     */
    val monthlyEquivalentPaise: Long
        get() = when (cadence) {
            Cadence.WEEKLY -> typicalPaise * 52 / 12
            Cadence.MONTHLY -> typicalPaise
            Cadence.QUARTERLY -> typicalPaise / 3
            Cadence.YEARLY -> typicalPaise / 12
        }

    enum class Cadence(
        val periodDays: Int,
        /** How far a single gap may stray and still count as this cadence. */
        val toleranceDays: Int,
        val label: String,
    ) {
        // Calendar months are 28–31 days, so monthly needs the widest window of the
        // four before anything else is considered.
        WEEKLY(7, 2, "Every week"),
        MONTHLY(30, 6, "Every month"),
        QUARTERLY(91, 12, "Every 3 months"),
        YEARLY(365, 25, "Every year"),
    }
}

/**
 * Finds charges that repeat.
 *
 * Deliberately conservative. A false positive here is worse than a miss: telling someone
 * they have a ₹4,000 monthly commitment they do not have makes every other number in the
 * app suspect, whereas failing to spot one subscription costs them nothing they had
 * before.
 */
object RecurringDetector {

    /**
     * How far back a commitment has to be looked for.
     *
     * A pattern needs history to be visible at all: three monthly sightings is four months of
     * data, and a yearly charge needs two years before it can be called yearly.
     *
     * It lives here rather than on a screen because **two screens depend on getting the same
     * answer.** Summary shows the commitments and Home paces the budget against them, and a Home
     * figure that disagreed with Summary's would be worse than no figure at all. Sharing the
     * window — and the detector — makes them agree by construction rather than by care.
     */
    const val LOOKBACK_MONTHS = 24L

    /** Two of anything is a coincidence. Three is the earliest a pattern can exist. */
    private const val MIN_OCCURRENCES = 3

    /**
     * How far each amount may sit from the median and still count as "the same charge".
     *
     * This is the guard that separates a subscription from a habit. Weekly groceries at
     * the same shop repeat just as regularly as Netflix does, but for wildly different
     * amounts — and they are not a commitment you could cancel. Requiring the amounts to
     * agree is what keeps the shop out and the subscription in.
     *
     * Generous enough at a quarter to survive a utility bill's seasonal swing and the odd
     * price rise.
     */
    private const val AMOUNT_TOLERANCE = 0.25

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /**
     * @param transactions any window of history; the longer, the better a yearly charge
     *   can be seen. Deleted rows and credits are filtered here rather than by callers.
     */
    fun detect(transactions: List<Transaction>): List<RecurringCharge> =
        transactions
            .asSequence()
            .filter { !it.deleted && it.type == TxnType.DEBIT }
            // A placeholder is not a payee. Grouping by it would pool every unnamed
            // debit in the household into one imaginary subscription.
            .filter { it.merchant.isNotBlank() && !it.merchant.equals(UNKNOWN, true) }
            .groupBy { it.merchant.lowercase().trim() }
            .values
            .mapNotNull { rows -> fromGroup(rows.sortedBy { it.occurredAt }) }
            // Biggest commitment first: the one worth cancelling is the one that costs
            // the most per month, not the one that happens to recur most often.
            .sortedByDescending { it.monthlyEquivalentPaise }

    private fun fromGroup(rows: List<Transaction>): RecurringCharge? {
        if (rows.size < MIN_OCCURRENCES) return null

        val median = rows.map { it.amountPaise }.median()
        if (median <= 0) return null
        if (rows.any { abs(it.amountPaise - median).toDouble() / median > AMOUNT_TOLERANCE }) {
            return null
        }

        val gaps = rows.zipWithNext { a, b -> (b.occurredAt - a.occurredAt) / DAY_MILLIS }
        if (gaps.any { it <= 0 }) return null

        val cadence = cadenceFor(gaps) ?: return null

        // Step from the last sighting by the cadence rather than by the average gap, so
        // a run that drifted early does not predict a date that keeps sliding.
        val last = rows.last()
        return RecurringCharge(
            merchant = last.merchant,
            category = rows.groupingBy { it.category }.eachCount()
                .maxByOrNull { it.value }?.key ?: last.category,
            typicalPaise = median,
            cadence = cadence,
            occurrences = rows.size,
            lastSeenAt = last.occurredAt,
            nextExpectedAt = last.occurredAt + cadence.periodDays * DAY_MILLIS,
        )
    }

    /**
     * The cadence every gap agrees on, or null.
     *
     * A gap of roughly twice the period is accepted as one missed sighting — a bank that
     * skipped an SMS, or a month the parser did not recognise the sender. Without that,
     * a single missing message would hide a subscription that has run for two years.
     * Three times is not allowed: by then the run is too sparse to call a pattern.
     */
    private fun cadenceFor(gaps: List<Long>): RecurringCharge.Cadence? =
        RecurringCharge.Cadence.entries.firstOrNull { cadence ->
            gaps.all { gap ->
                (1..2).any { multiple ->
                    abs(gap - cadence.periodDays.toLong() * multiple) <=
                        cadence.toleranceDays.toLong() * multiple
                }
            }
        }

    private fun List<Long>.median(): Long {
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private const val UNKNOWN = "Unknown payee"
}
