package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.Pacing
import java.time.LocalDate
import org.junit.Test

/**
 * The budget pacing, against the household's own figures.
 *
 * Two scenarios matter more than the rest, and they pull in opposite directions:
 *
 *  - **6 August.** 74% of the month's budget gone on the 6th of 31 days. The app reported this in
 *    green and said nothing. It must be called tight.
 *  - **Rent on the 1st.** ₹16,955 — 59% of the month's spending — on day 3, because rent and a card
 *    bill landed together. That is a household doing exactly the right thing and it must *not* be
 *    called tight, or the alert is wrong every month and gets switched off.
 *
 * Getting both right is the whole design of [Pacing], and the second one caught the first version
 * of the model being wrong.
 */
class PacingTest {

    private fun at(day: Int, month: Int = 8, year: Int = 2026): Long =
        OursZone.startOfDay(LocalDate.of(year, month, day)) + 9 * 3_600_000L

    private fun rupees(n: Long) = n * 100

    @Test
    fun `no budget means nothing to pace against`() {
        assertThat(
            Pacing.of(rupees(1000), null, rupees(1000), rupees(500), at(6))
        ).isNull()
        assertThat(
            Pacing.of(rupees(1000), 0, rupees(1000), rupees(500), at(6))
        ).isNull()
    }

    /** The case the review found: three quarters spent on the sixth. */
    @Test
    fun `the sixth of August, three quarters spent, is reported as tight`() {
        val result = Pacing.of(
            spentPaise = 2_876_320,                  // ₹28,763.20
            budgetPaise = rupees(38_500),
            monthlyCommittedPaise = rupees(8_940),   // what the Committed panel shows
            committedRemainingPaise = rupees(2_400), // not yet paid
            now = at(6),
        )!!
        assertThat(result.state).isEqualTo(Pacing.State.Tight)
        assertThat(result.daysRemaining).isEqualTo(26)   // 31 − 6 + 1
        // discretionary budget ₹29,560; discretionary spent ₹28,763.20 − ₹6,540 = ₹22,223.20
        // so ₹7,336.80 over 26 days
        assertThat(result.perDayPaise).isEqualTo(733_680 / 26)
        assertThat(result.perDayPaise!!).isLessThan(rupees(300))
    }

    /**
     * The false alarm a linear model produces, and this one must not.
     *
     * The whole ₹16,955 is recognised as a commitment that has been *paid*, so paying it moves
     * `spent` and `committedPaid` by the same amount and the discretionary position does not move.
     */
    @Test
    fun `rent paid on the first does not make the month look overspent`() {
        val result = Pacing.of(
            spentPaise = rupees(16_955),
            budgetPaise = rupees(38_500),
            monthlyCommittedPaise = rupees(16_955),
            committedRemainingPaise = 0,             // all of it paid
            now = at(4),
        )!!
        assertThat(result.state).isEqualTo(Pacing.State.OnCourse)
        // The full discretionary budget is still intact: ₹21,545 over 28 days.
        assertThat(result.perDayPaise).isEqualTo(rupees(21_545) / 28)
    }

    /** And the same day, with the same total spent, but none of it committed. */
    @Test
    fun `the same spend that is not committed is tight`() {
        val result = Pacing.of(
            spentPaise = rupees(16_955),
            budgetPaise = rupees(38_500),
            monthlyCommittedPaise = 0,
            committedRemainingPaise = 0,
            now = at(4),
        )!!
        assertThat(result.state).isEqualTo(Pacing.State.Tight)
    }

    @Test
    fun `an untouched month at the start is on course`() {
        val result = Pacing.of(
            spentPaise = 0,
            budgetPaise = rupees(38_500),
            monthlyCommittedPaise = rupees(8_940),
            committedRemainingPaise = rupees(8_940),
            now = at(1),
        )!!
        assertThat(result.state).isEqualTo(Pacing.State.OnCourse)
        assertThat(result.daysRemaining).isEqualTo(31)
        assertThat(result.perDayPaise).isEqualTo(rupees(38_500 - 8_940) / 31)
    }

    @Test
    fun `commitments outrunning what is left reports a shortfall, not an allowance`() {
        val result = Pacing.of(
            spentPaise = rupees(36_900),
            budgetPaise = rupees(38_500),
            monthlyCommittedPaise = rupees(8_940),
            committedRemainingPaise = rupees(2_400),
            now = at(20),
        )!!
        assertThat(result.state).isEqualTo(Pacing.State.Short)
        // A negative divided by days would read as a daily allowance, so there is none.
        assertThat(result.perDayPaise).isNull()
        // ₹1,600 left against ₹2,400 still owed.
        assertThat(result.shortfallPaise).isEqualTo(rupees(800))
    }

    /**
     * This asserted `Short`, and the change is deliberate.
     *
     * `Short` means "the cap cannot cover what is still owed". With no commitments at all
     * the old condition reduced to plain `budgetLeft < 0`, and `BudgetAlerter` rendered it
     * as "Not enough left for this month's commitments — ₹0 still due and ₹0 left ·
     * ₹1,500 short": two of three figures zero, describing a shortfall against nothing.
     *
     * Being over budget is a different condition, and one the screen already states
     * plainly — Home's budget row reads "USED 153% · OVER ₹20,649" — so nothing is lost by
     * pacing reporting what it actually knows: there is ₹0 a day left, which is Tight.
     */
    @Test
    fun `over budget with nothing owed is tight, not a shortfall`() {
        val result = Pacing.of(
            spentPaise = rupees(40_000),
            budgetPaise = rupees(38_500),
            monthlyCommittedPaise = 0,
            committedRemainingPaise = 0,
            now = at(15),
        )!!
        assertThat(result.state).isEqualTo(Pacing.State.Tight)
        assertThat(result.perDayPaise).isEqualTo(0)
        assertThat(result.shortfallPaise).isEqualTo(0)
    }

    @Test
    fun `the last day of the month still divides by one, never zero`() {
        val result = Pacing.of(
            spentPaise = rupees(30_000),
            budgetPaise = rupees(38_500),
            monthlyCommittedPaise = 0,
            committedRemainingPaise = 0,
            now = at(31),
        )!!
        assertThat(result.daysRemaining).isEqualTo(1)
        assertThat(result.perDayPaise).isEqualTo(rupees(8_500))
    }

    /** February, so the month length is read from the calendar rather than assumed. */
    @Test
    fun `month length comes from the calendar`() {
        val result = Pacing.of(
            spentPaise = 0,
            budgetPaise = rupees(28_000),
            monthlyCommittedPaise = 0,
            committedRemainingPaise = 0,
            now = at(day = 1, month = 2, year = 2027),
        )!!
        assertThat(result.daysRemaining).isEqualTo(28)
        assertThat(result.perDayPaise).isEqualTo(rupees(1_000))
    }

    /**
     * A charge paid early is counted in both inputs at once.
     *
     * `monthlyCommitted` is a cadence-reconciled estimate and `committedRemaining` is a date
     * filter, so the two can briefly disagree. Without clamping, `committedPaid` would go negative
     * and inflate the discretionary position — reporting a healthier month than there is.
     */
    @Test
    fun `inconsistent commitment inputs cannot inflate the position`() {
        val result = Pacing.of(
            spentPaise = rupees(5_000),
            budgetPaise = rupees(38_500),
            monthlyCommittedPaise = rupees(2_000),
            committedRemainingPaise = rupees(8_000),   // larger than the monthly total
            now = at(10),
        )!!
        assertThat(result.perDayPaise!!).isAtMost(rupees(38_500) / result.daysRemaining)
        assertThat(result.state).isAnyOf(Pacing.State.OnCourse, Pacing.State.Tight)
    }
}
