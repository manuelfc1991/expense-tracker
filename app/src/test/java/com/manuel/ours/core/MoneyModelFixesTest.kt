package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.Pacing
import com.manuel.ours.domain.RecurringCharge
import com.manuel.ours.domain.affordability
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import java.time.LocalDate
import org.junit.Test

/**
 * Four money-model defects found in a QA pass, each proved here before it was fixed.
 */
class MoneyModelFixesTest {

    private fun account(key: String, usable: Long?, card: Boolean = false) = AccountBalance(
        key = key,
        accountTail = key,
        bank = if (card) "Utkarsh SuperCard" else "Kerala Gramin Bank",
        balancePaise = usable,
        asOf = 1_000L,
        source = null,
        ownerName = "Manuel",
        isCard = card,
    )

    // ── 1 · card debt must never be spendable capacity ───────────────────────

    /**
     * The Accounts panel partitions cards out; `affordability` did not, and both call
     * sites hand it the unpartitioned list. So the figure the app spends against counted
     * a debt as money, with the sign inverted.
     */
    @Test
    fun `a credit card balance is not money you can spend`() {
        val result = affordability(
            budgetPaise = 38_500_00,
            householdSpentPaise = 0,
            balances = listOf(
                account("3062", 9_649_00),
                account("8842", 4_200_00, card = true),
            ),
        )
        assertThat(result.usablePaise).isEqualTo(9_649_00)
    }

    @Test
    fun `a card with no balance is not an unknown account`() {
        val result = affordability(
            budgetPaise = null,
            householdSpentPaise = 0,
            balances = listOf(account("3062", 9_649_00), account("8842", null, card = true)),
        )
        assertThat(result.unknownAccounts).isEqualTo(0)
    }

    // ── 2 · a charge already paid this month is not still owed ───────────────

    /**
     * `nextExpectedAt = lastSeenAt + 30 days`, so rent paid on the 1st of a 31-day month
     * lands back inside the same month and was counted as still to come — the exact false
     * alarm `Pacing` was written to avoid, and it recurs in Jan, Mar, May, Jul, Aug, Oct
     * and Dec.
     */
    @Test
    fun `rent paid on the first is not still owed on the seventh`() {
        val paidOn1Aug = OursZone.startOfDay(LocalDate.of(2026, 8, 1)) + 9 * 3_600_000L
        val charge = RecurringCharge(
            merchant = "Rent",
            category = Category.RENT,
            typicalPaise = 15_000_00,
            cadence = RecurringCharge.Cadence.MONTHLY,
            lastSeenAt = paidOn1Aug,
            nextExpectedAt = paidOn1Aug + 30L * 24 * 3_600_000L,
            occurrences = 6,
        )
        val now = OursZone.startOfDay(LocalDate.of(2026, 8, 7)) + 9 * 3_600_000L
        assertThat(MonthlyAggregator.committedRemaining(listOf(charge), now)).isEqualTo(0)
    }

    /** A charge genuinely still to come this month is still counted. */
    @Test
    fun `a charge not yet paid this month is still owed`() {
        val paidOn20Jul = OursZone.startOfDay(LocalDate.of(2026, 7, 20)) + 9 * 3_600_000L
        val charge = RecurringCharge(
            merchant = "Broadband",
            category = Category.BILLS,
            typicalPaise = 1_200_00,
            cadence = RecurringCharge.Cadence.MONTHLY,
            lastSeenAt = paidOn20Jul,
            nextExpectedAt = paidOn20Jul + 30L * 24 * 3_600_000L,
            occurrences = 6,
        )
        val now = OursZone.startOfDay(LocalDate.of(2026, 8, 7)) + 9 * 3_600_000L
        assertThat(MonthlyAggregator.committedRemaining(listOf(charge), now))
            .isEqualTo(1_200_00)
    }

    // ── 3 · over budget with no commitments is not "short on commitments" ────

    /**
     * `Short` tests `budgetLeft - committedRemaining < 0`, which is plain over-budget
     * when there are no commitments at all. The alert then read "₹0 still due and ₹0
     * left · ₹5,000 short" — two of its three figures zero.
     */
    @Test
    fun `plain over budget with no commitments is not reported as a shortfall`() {
        val result = Pacing.of(
            spentPaise = 43_500_00,
            budgetPaise = 38_500_00,
            monthlyCommittedPaise = 0,
            committedRemainingPaise = 0,
            now = OursZone.startOfDay(LocalDate.of(2026, 8, 15)) + 9 * 3_600_000L,
        )!!
        assertThat(result.state).isNotEqualTo(Pacing.State.Short)
    }

    /** A real shortfall against real commitments still reports one. */
    @Test
    fun `a genuine commitment shortfall is still reported`() {
        val result = Pacing.of(
            spentPaise = 36_900_00,
            budgetPaise = 38_500_00,
            monthlyCommittedPaise = 8_940_00,
            committedRemainingPaise = 2_400_00,
            now = OursZone.startOfDay(LocalDate.of(2026, 8, 20)) + 9 * 3_600_000L,
        )!!
        assertThat(result.state).isEqualTo(Pacing.State.Short)
        assertThat(result.shortfallPaise).isEqualTo(800_00)
    }

    // ── 4 · the biggest expense must net its refund ──────────────────────────

    @Test
    fun `a fully refunded purchase is not the month's biggest expense`() {
        fun debit(rupees: Long, merchant: String, refunded: Long = 0) = Transaction(
            id = merchant,
            amountPaise = rupees * 100,
            type = TxnType.DEBIT,
            merchant = merchant,
            category = Category.SHOPPING,
            occurredAt = OursZone.startOfDay(LocalDate.of(2026, 8, 4)),
            ownerUid = "me",
            ownerName = "Manuel",
            splitType = SplitType.SHARED,
            source = TxnSource.SMS,
            refundedPaise = refunded * 100,
        )
        val ledger = listOf(
            debit(22_000, "Laptop", refunded = 22_000),
            debit(15_000, "Rent"),
        )
        assertThat(MonthlyAggregator.biggestExpense(ledger)?.merchant).isEqualTo("Rent")
    }
}
