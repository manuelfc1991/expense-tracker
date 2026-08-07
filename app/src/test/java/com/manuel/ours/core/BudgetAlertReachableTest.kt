package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.Pacing
import java.time.LocalDate
import org.junit.Test

/**
 * "Over your monthly budget" has to be reachable.
 *
 * Two things conspired to make it unreachable for the overall cap. `Pacing.Short` fired on
 * plain over-budget, because its condition reduced to `budgetLeft < 0` when nothing was
 * owed; and `BudgetAlerter`'s `continue` sat outside the `if` that adds the alert, so once
 * *any* pace alert had fired the loop skipped the percentage thresholds for the rest of the
 * month. Between them, a household could go to 153% of a ₹38,500 cap and never be told.
 *
 * The alerter itself needs DataStore and a DAO, so what is pinned here is the pacing half —
 * the condition that made every over-budget month look like a commitment shortfall.
 */
class BudgetAlertReachableTest {

    private fun at(day: Int) =
        OursZone.startOfDay(LocalDate.of(2026, 8, day)) + 9 * 3_600_000L

    /**
     * The real numbers from this household's 6 August: ₹29,260 spent against ₹38,500 with
     * nothing owed. Must not be a shortfall, and must leave a real per-day figure.
     */
    @Test
    fun `a normal month with no commitments is never a shortfall`() {
        val result = Pacing.of(
            spentPaise = 29_260_20,
            budgetPaise = 38_500_00,
            monthlyCommittedPaise = 0,
            committedRemainingPaise = 0,
            now = at(7),
        )!!
        assertThat(result.state).isNotEqualTo(Pacing.State.Short)
        assertThat(result.perDayPaise).isNotNull()
        assertThat(result.perDayPaise!!).isGreaterThan(0)
    }

    /**
     * Over budget, nothing owed. `Short` would send the alerter down the
     * "not enough for your commitments" branch and, before the fix, permanently suppress
     * the percentage alert that should have fired instead.
     */
    @Test
    fun `over budget with nothing owed leaves the percentage alert to do its job`() {
        val result = Pacing.of(
            spentPaise = 41_000_00,
            budgetPaise = 38_500_00,
            monthlyCommittedPaise = 0,
            committedRemainingPaise = 0,
            now = at(20),
        )!!
        assertThat(result.state).isNotEqualTo(Pacing.State.Short)
        assertThat(result.shortfallPaise).isEqualTo(0)
    }

    /** And a real shortfall still is one, so the fix did not simply delete the state. */
    @Test
    fun `a real commitment shortfall still reports Short`() {
        val result = Pacing.of(
            spentPaise = 36_900_00,
            budgetPaise = 38_500_00,
            monthlyCommittedPaise = 8_940_00,
            committedRemainingPaise = 2_400_00,
            now = at(20),
        )!!
        assertThat(result.state).isEqualTo(Pacing.State.Short)
        assertThat(result.committedPaise).isEqualTo(2_400_00)
        assertThat(result.shortfallPaise).isEqualTo(800_00)
    }
}
