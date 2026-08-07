package com.manuel.ours.domain

import com.manuel.ours.core.OursZone
import com.manuel.ours.domain.model.CardInfo
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.YearMonth

/**
 * Which bills are worth interrupting somebody about today.
 *
 * The app already knew about due dates and never said anything. A statement SMS arriving
 * on the 1st for a bill due on the 15th wrote a row that appeared on Home immediately —
 * the moment it is least useful — and then stayed silent for a fortnight. Nothing was
 * scheduled, no notification existed, and the household found out by opening the app on
 * the right day or not at all.
 *
 * Kept as a pure function over an explicit `today` because every defect in this area is a
 * date-arithmetic defect: a month with no 30th, a due day that has already gone past this
 * month, a bill that falls due on the day the worker happens not to run. Those are cheap
 * to test here and expensive to test through a Worker and a NotificationManager.
 */
object DueBills {

    /** How many days ahead the first warning goes out. */
    const val WARN_DAYS = 3L

    /**
     * Two chances, and only two.
     *
     * [Ahead] is the one that can still change anything — three days is enough to move
     * money between accounts, which this household actually does to hold a minimum
     * balance. [Today] is the last call. Anything more becomes a notification people
     * learn to swipe away, and an alert that gets swiped unread has cost exactly the
     * protection it was added for.
     */
    enum class Stage { Ahead, Today }

    /**
     * One bill, on one date, at one stage.
     *
     * @param key stable across runs and unique per (bill, date, stage). It is what stops
     *   a reboot, a rescan or a second run on the same day from notifying twice, so it
     *   must not contain anything that varies between runs — no timestamps, no amounts
     *   that a later message might correct.
     */
    data class Alert(
        val key: String,
        val label: String,
        val amountPaise: Long?,
        val dueOn: LocalDate,
        val stage: Stage,
    ) {
        /** Days from today to [dueOn], for wording the notification. */
        fun daysAway(today: LocalDate): Long = ChronoUnit.DAYS.between(today, dueOn)
    }

    /** A bill the parser found in a message, with a real date attached. */
    data class Detected(val id: String, val label: String, val amountPaise: Long?, val dueAt: Long)

    /**
     * Everything due at [today], from both sources, with nothing said twice.
     *
     * @param alreadyFired keys returned by an earlier run. A key in here is skipped, which
     *   is what makes running this daily — or twice daily, or again after a reboot — safe.
     */
    fun due(
        detected: List<Detected>,
        cards: Map<String, CardInfo>,
        cardLabels: Map<String, String> = emptyMap(),
        today: LocalDate,
        alreadyFired: Set<String> = emptySet(),
    ): List<Alert> {
        val out = mutableListOf<Alert>()

        for (bill in detected) {
            val dueOn = OursZone.dateOf(bill.dueAt)
            stageFor(today, dueOn)?.let { stage ->
                out += Alert(
                    // The bill's own id already encodes bank, amount and date.
                    key = "bill:${bill.id}:${stage.name}",
                    label = bill.label,
                    amountPaise = bill.amountPaise,
                    dueOn = dueOn,
                    stage = stage,
                )
            }
        }

        for ((key, card) in cards) {
            val day = card.dueDay ?: continue
            val dueOn = nextDue(day, today) ?: continue
            stageFor(today, dueOn)?.let { stage ->
                out += Alert(
                    // Keyed by the date, not by the month, so a card due on the 2nd is
                    // not confused with the same card due on the 2nd of the next month.
                    key = "card:$key:$dueOn:${stage.name}",
                    label = cardLabels[key] ?: "Card ···$key",
                    // A due day says when, never how much. Reporting a figure here would
                    // mean inventing one, and this app does not sum a balance it was not
                    // told — see the Accounts tab, which reports unknown as unknown.
                    amountPaise = null,
                    dueOn = dueOn,
                    stage = stage,
                )
            }
        }

        return out.filterNot { it.key in alreadyFired }.sortedBy { it.dueOn }
    }

    /** Null when [dueOn] is neither three days off nor today — including when it is past. */
    private fun stageFor(today: LocalDate, dueOn: LocalDate): Stage? =
        when (ChronoUnit.DAYS.between(today, dueOn)) {
            0L -> Stage.Today
            WARN_DAYS -> Stage.Ahead
            else -> null
        }

    /**
     * The next time a monthly [day] comes round, counting today as still to come.
     *
     * Clamped to the length of the month, so a card due on the 31st falls due on the 30th
     * in November and the 28th in a February — the alternative is `LocalDate.of` throwing
     * inside a background worker, which would take the notification down for every other
     * bill along with it.
     */
    fun nextDue(day: Int, today: LocalDate): LocalDate? {
        if (day !in 1..31) return null
        val thisMonth = onOrBefore(YearMonth.from(today), day)
        return if (!thisMonth.isBefore(today)) thisMonth
        else onOrBefore(YearMonth.from(today).plusMonths(1), day)
    }

    private fun onOrBefore(month: YearMonth, day: Int): LocalDate =
        month.atDay(minOf(day, month.lengthOfMonth()))
}
