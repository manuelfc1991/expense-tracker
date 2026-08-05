package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.Affordability
import com.manuel.ours.domain.affordability
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.BalanceSource
import org.junit.Test

/**
 * The budget against the bank.
 *
 * These are the rules the two screens now share, and the cases below are the ones that
 * were previously answered differently — or not at all — depending on which screen you
 * happened to be looking at.
 */
class AffordabilityTest {

    private fun rupees(amount: Long) = amount * 100

    private fun account(
        key: String,
        balance: Long?,
        minimum: Long = 0,
    ) = AccountBalance(
        key = key,
        accountTail = key,
        bank = key,
        balancePaise = balance?.let { rupees(it) },
        asOf = if (balance == null) null else 1_000L,
        source = if (balance == null) null else BalanceSource.BANK,
        ownerName = "Manuel",
        minimumPaise = rupees(minimum),
    )

    @Test
    fun `the money is the limit when the budget allows more than the accounts hold`() {
        // ₹40,000 budget, ₹22,000 spent — ₹18,000 of permission left. But the accounts
        // hold ₹10,149 and ₹3,000 of that must stay in Federal.
        val result = affordability(
            budgetPaise = rupees(40_000),
            householdSpentPaise = rupees(22_000),
            balances = listOf(
                account("federal", balance = 10_149, minimum = 3_000),
            ),
        )

        assertThat(result.budgetLeftPaise).isEqualTo(rupees(18_000))
        assertThat(result.usablePaise).isEqualTo(rupees(7_149))
        assertThat(result.limit).isEqualTo(Affordability.Limit.BALANCE)
        assertThat(result.safeToSpendPaise).isEqualTo(rupees(7_149))
        // The number that was impossible to get before: how much the plan is promising
        // that the household cannot actually produce.
        assertThat(result.gapPaise).isEqualTo(rupees(10_851))
        assertThat(result.budgetOutrunsMoney).isTrue()
    }

    @Test
    fun `the budget is the limit when there is money to spare`() {
        val result = affordability(
            budgetPaise = rupees(40_000),
            householdSpentPaise = rupees(38_000),
            balances = listOf(account("gramin", balance = 50_000)),
        )

        assertThat(result.limit).isEqualTo(Affordability.Limit.BUDGET)
        assertThat(result.safeToSpendPaise).isEqualTo(rupees(2_000))
        assertThat(result.budgetOutrunsMoney).isFalse()
    }

    @Test
    fun `commitments come off the money, not off the budget`() {
        // Rent of ₹8,000 still due. It is money in the account that cannot be spent, so
        // capacity drops — but the budget has not been charged for it yet, and charging
        // both would take the same rupee twice.
        val result = affordability(
            budgetPaise = rupees(40_000),
            householdSpentPaise = rupees(10_000),
            balances = listOf(account("federal", balance = 20_000)),
            committedRemainingPaise = rupees(8_000),
        )

        assertThat(result.budgetLeftPaise).isEqualTo(rupees(30_000))
        assertThat(result.usablePaise).isEqualTo(rupees(20_000))
        assertThat(result.afterCommitmentsPaise).isEqualTo(rupees(12_000))
        assertThat(result.safeToSpendPaise).isEqualTo(rupees(12_000))
    }

    @Test
    fun `over budget still reports what is physically there`() {
        val result = affordability(
            budgetPaise = rupees(40_000),
            householdSpentPaise = rupees(45_000),
            balances = listOf(account("gramin", balance = 12_000)),
        )

        assertThat(result.overBudget).isTrue()
        assertThat(result.budgetLeftPaise).isEqualTo(rupees(-5_000))
        // Never negative. "You may spend minus ₹5,000" is not a sentence anyone can act
        // on, and the over-budget figure is reported separately in its own words.
        assertThat(result.safeToSpendPaise).isEqualTo(0L)
        // Over budget is a decision, not a wall — the money is still there, and the
        // screen says so rather than implying the card will be declined.
        assertThat(result.usablePaise).isEqualTo(rupees(12_000))
    }

    @Test
    fun `an account with no balance is unknown, never zero`() {
        val result = affordability(
            budgetPaise = rupees(40_000),
            householdSpentPaise = rupees(10_000),
            balances = listOf(
                account("federal", balance = 5_000),
                account("icici", balance = null),
            ),
        )

        // The known one counts and the unknown one is reported rather than summed as 0 —
        // which would have understated the household's money and, worse, done it
        // silently.
        assertThat(result.usablePaise).isEqualTo(rupees(5_000))
        assertThat(result.unknownAccounts).isEqualTo(1)
    }

    @Test
    fun `no balances at all leaves capacity unknown rather than empty`() {
        val result = affordability(
            budgetPaise = rupees(40_000),
            householdSpentPaise = rupees(10_000),
            balances = listOf(account("icici", balance = null)),
        )

        assertThat(result.usablePaise).isNull()
        // Capacity is unknown, so it cannot be the binding constraint. Treating it as
        // zero would have declared a household with a fresh budget unable to spend.
        assertThat(result.limit).isEqualTo(Affordability.Limit.BUDGET)
        assertThat(result.safeToSpendPaise).isEqualTo(rupees(30_000))
    }

    @Test
    fun `no budget set falls back to what the accounts hold`() {
        val result = affordability(
            budgetPaise = null,
            householdSpentPaise = rupees(10_000),
            balances = listOf(account("federal", balance = 9_000, minimum = 3_000)),
        )

        assertThat(result.budgetLeftPaise).isNull()
        assertThat(result.limit).isEqualTo(Affordability.Limit.BALANCE)
        assertThat(result.safeToSpendPaise).isEqualTo(rupees(6_000))
    }

    @Test
    fun `a zero budget is no budget`() {
        // Otherwise every household that had never set one would be permanently and
        // uselessly over its cap.
        val result = affordability(
            budgetPaise = 0L,
            householdSpentPaise = rupees(10_000),
            balances = listOf(account("federal", balance = 9_000)),
        )

        assertThat(result.budgetPaise).isNull()
        assertThat(result.overBudget).isFalse()
        assertThat(result.limit).isEqualTo(Affordability.Limit.BALANCE)
    }

    @Test
    fun `nothing known at all yields no figure rather than a made-up one`() {
        val result = affordability(
            budgetPaise = null,
            householdSpentPaise = rupees(10_000),
            balances = emptyList(),
        )

        assertThat(result.safeToSpendPaise).isNull()
        assertThat(result.limit).isEqualTo(Affordability.Limit.NONE)
    }

    @Test
    fun `a tie goes to the budget`() {
        // Equal figures mean the household can afford exactly what it planned. Calling
        // that a money problem would tell them to go and find more of it.
        val result = affordability(
            budgetPaise = rupees(40_000),
            householdSpentPaise = rupees(30_000),
            balances = listOf(account("federal", balance = 10_000)),
        )

        assertThat(result.limit).isEqualTo(Affordability.Limit.BUDGET)
        assertThat(result.budgetOutrunsMoney).isFalse()
        assertThat(result.gapPaise).isEqualTo(0L)
    }

    @Test
    fun `a balance under the bank's minimum contributes nothing rather than a debt`() {
        // Kerala Gramin demands ₹500 and the account holds ₹300. That is ₹0 available,
        // not ₹200 of negative money to be subtracted from the other accounts.
        val result = affordability(
            budgetPaise = rupees(40_000),
            householdSpentPaise = 0L,
            balances = listOf(
                account("gramin", balance = 300, minimum = 500),
                account("federal", balance = 5_000, minimum = 3_000),
            ),
        )

        assertThat(result.usablePaise).isEqualTo(rupees(2_000))
    }
}
