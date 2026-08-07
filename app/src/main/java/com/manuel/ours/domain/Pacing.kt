package com.manuel.ours.domain

import com.manuel.ours.core.OursZone
import java.time.YearMonth
import kotlin.math.max

/**
 * What can be spent today without missing the cap.
 *
 * ## The problem this solves
 *
 * `docs/REVIEW.md` §1. On the morning that review was written, Home read
 *
 * > ₹28,763.20 spent · budget ₹38.5K · **74% used** · ₹9,710 left — meter green
 *
 * on the **6th of a 31-day month**, where an even pace would be about 19%. The screen said
 * nothing, because nothing in the budget path took the date as an input: the fraction was
 * `spent / budget`, and [BudgetAlerter] fired at a flat 80 and 100 — identically on the 3rd and
 * the 28th. Telling a household whether it is *on course* is the entire job of a budget, and it
 * was the one question the app could not answer.
 *
 * ## Why the obvious fix is the wrong one
 *
 * Dividing the budget by days elapsed would cry wolf every month in this household. Its own daily
 * bars for August show **₹16,955.79 on day 3 — 59% of the month's spending in a single day** —
 * which is rent and a card bill landing together. Any linear pace model reports a household that
 * pays rent on the 1st as catastrophically overspent for the first fortnight, every single month,
 * and an alert that is wrong every month is an alert people switch off.
 *
 * ## What this does instead
 *
 * Paces the **discretionary** money: `(budgetLeft − stillCommitted) ÷ daysRemaining`. Rent on the
 * 1st never triggers it, because rent was *committed* and is now paid —
 * [MonthlyAggregator.committedRemaining] already excludes anything whose date has passed.
 *
 * Every input already existed. Nothing here is new data; it is arithmetic nobody was doing.
 */
object Pacing {

    /**
     * How the daily figure should be read, which the number alone does not say.
     *
     * The same ₹292 is reassuring on the 28th and alarming on the 6th, so the state is computed
     * against what an even pace *would* leave rather than against a fixed threshold.
     */
    enum class State {
        /** The daily allowance is comfortable. Nothing needs saying. */
        OnCourse,

        /** Noticeably below an even pace. Worth one sentence, and worth alerting on once. */
        Tight,

        /**
         * Commitments already outrun what is left.
         *
         * There is no daily figure here: dividing into a negative produces a number that reads
         * like an allowance. The shortfall is reported instead.
         */
        Short,
    }

    data class Result(
        val state: State,
        /** Null when [state] is [State.Short] — see above. */
        val perDayPaise: Long?,
        val daysRemaining: Int,
        val committedPaise: Long,
        val budgetLeftPaise: Long,
        /** How far commitments exceed what is left. Only meaningful when [State.Short]. */
        val shortfallPaise: Long,
    )

    /**
     * Below this share of an even pace, the month is called tight.
     *
     * 0.8 rather than 1.0 because being slightly under an even pace is the normal condition of a
     * household that pays its fixed costs early, and this must not fire for that.
     */
    private const val TIGHT_BELOW = 0.8

    /**
     * Both halves of the commitment figure are needed, and that is the subtle part.
     *
     * The first version of this took only what was *still* committed, and its own test caught it
     * crying wolf in exactly the case the review warns about: ₹16,955 of rent and a card bill paid
     * on day 3 left the remaining-per-day well under a whole-month even pace, so a household doing
     * precisely the right thing was told the month was tight.
     *
     * The reason is that once a fixed cost is *paid* it vanishes from `committedRemaining`, and the
     * model can no longer tell "₹16,955 was rent" from "₹16,955 was impulse shopping". So the
     * reference has to be discretionary too, and that needs the month's whole commitment:
     *
     *     discretionaryBudget = budget − monthlyCommitted
     *     committedPaid       = monthlyCommitted − committedRemaining
     *     discretionarySpent  = spent − committedPaid
     *
     * Now rent leaving the account moves `spent` and `committedPaid` by the same amount, so the
     * discretionary position does not move at all — which is the correct answer.
     *
     * @param spentPaise the household's spending so far this month
     * @param budgetPaise the overall cap, or null if none is set
     * @param monthlyCommittedPaise every recurring charge's monthly equivalent, paid or not —
     *   `recurring.sumOf { it.monthlyEquivalentPaise }`, the figure the Committed panel shows
     * @param committedRemainingPaise the part not yet paid, from
     *   [MonthlyAggregator.committedRemaining]
     * @param now the instant to reckon from, injected so this is testable
     */
    fun of(
        spentPaise: Long,
        budgetPaise: Long?,
        monthlyCommittedPaise: Long,
        committedRemainingPaise: Long,
        now: Long = System.currentTimeMillis(),
    ): Result? {
        if (budgetPaise == null || budgetPaise <= 0) return null

        val today = OursZone.dateOf(now)
        val lengthOfMonth = YearMonth.from(today).lengthOfMonth()
        // Today counts: money can still be spent on it. On the last day of the month this is 1,
        // never 0, so the division below is always safe.
        val daysRemaining = lengthOfMonth - today.dayOfMonth + 1

        val budgetLeft = budgetPaise - spentPaise

        // Clamped, because both inputs are estimates from different places: a charge can be paid
        // early and still be counted as remaining, which would otherwise make `committedPaid`
        // negative and inflate the discretionary position.
        val monthlyCommitted = monthlyCommittedPaise.coerceAtLeast(committedRemainingPaise)
        val committedPaid = (monthlyCommitted - committedRemainingPaise).coerceAtLeast(0)

        val discretionaryBudget = (budgetPaise - monthlyCommitted).coerceAtLeast(0)
        val discretionarySpent = (spentPaise - committedPaid).coerceAtLeast(0)
        val discretionaryLeft = discretionaryBudget - discretionarySpent

        // A shortfall is reckoned on the real money, not the discretionary abstraction: what
        // matters is that the cap cannot cover what is still owed.
        if (budgetLeft - committedRemainingPaise < 0) {
            return Result(
                state = State.Short,
                perDayPaise = null,
                daysRemaining = daysRemaining,
                committedPaise = committedRemainingPaise,
                budgetLeftPaise = budgetLeft,
                shortfallPaise = committedRemainingPaise - budgetLeft,
            )
        }

        val perDay = (discretionaryLeft.coerceAtLeast(0)) / daysRemaining

        // What an even pace would leave per day. Measured over the whole month against the
        // discretionary budget, so the reference does not drift with the answer.
        val evenPerDay = max(1L, discretionaryBudget / lengthOfMonth)
        val state = if (perDay < evenPerDay * TIGHT_BELOW) State.Tight else State.OnCourse

        return Result(
            state = state,
            perDayPaise = perDay,
            daysRemaining = daysRemaining,
            committedPaise = committedRemainingPaise,
            budgetLeftPaise = budgetLeft,
            shortfallPaise = 0,
        )
    }
}
