package com.manuel.ours.domain

import com.manuel.ours.domain.model.AccountBalance

/**
 * The budget and the bank balance, finally in the same sentence.
 *
 * These are two different kinds of number and the app had been showing both without ever
 * saying how they relate — a ₹40,000 budget on Home and a "What is left" total on
 * Summary, each labelled some variant of *left*, each computed from data the other knows
 * nothing about. Asked which one says what you can spend, the honest answer was neither.
 *
 * They are not two estimates of one quantity. They are:
 *
 *  - **The budget: permission.** A cap the household chose. It moves only when money is
 *    *consumed*, so it is unaffected by moving ₹5,000 into savings or shifting money
 *    between our own accounts — those leave the balance and never touch the budget. This
 *    is why the two figures drift apart during a month, and it is correct that they do.
 *  - **The balance: capacity.** What the banks actually hold, less the minimums they
 *    make us keep. It falls for every rupee that leaves, whatever it was for.
 *
 * You can only spend the **smaller** of the two, so that is the figure, and [limit] names
 * which one is doing the binding. A budget with no money behind it is a wish; money with
 * no budget left is a decision to overspend. Both are worth knowing, and neither can be
 * read off the other.
 *
 * Commitments come off the balance side rather than the budget side. A subscription due
 * on the 28th is money you have and cannot spend, which is a fact about capacity; the
 * budget already counts it once it is actually paid, and taking it off both would charge
 * the household twice for the same rupee.
 *
 * Every figure here is nullable-aware on purpose: an unset budget and an account nobody
 * has a balance for are both *unknown*, not zero, and a screen that renders unknown as
 * zero is the specific way this app would lose someone's trust.
 */
data class Affordability(
    /** The household's monthly cap, or null if nobody has set one. */
    val budgetPaise: Long?,
    /** Spending this month, household-wide — never one member's share. See [limit]. */
    val spentPaise: Long,
    /** Balances less minimums, or null when no account has a known figure at all. */
    val usablePaise: Long?,
    /** Accounts the app knows exist and has never been told the balance of. */
    val unknownAccounts: Int,
    /** Recurring charges expected before the month ends and not yet paid. */
    val committedPaise: Long,
    /**
     * True when the viewer is not the household owner, so only their own accounts are
     * counted. The capacity side is then a floor rather than a total, and the screen has
     * to say so — a partner reading "you can spend ₹9,000" should know the household may
     * well have more.
     */
    val partialView: Boolean,
) {
    /** Permission remaining. Negative once the household is over its cap. */
    val budgetLeftPaise: Long? get() = budgetPaise?.let { it - spentPaise }

    val overBudget: Boolean get() = (budgetLeftPaise ?: 0L) < 0L

    /** Capacity remaining, once money already promised to somebody else is set aside. */
    val afterCommitmentsPaise: Long? get() = usablePaise?.minus(committedPaise)

    /**
     * What can actually be spent: the lesser of permission and capacity, never below
     * zero.
     *
     * Null only when neither is known — no budget set and no balance recorded — because
     * inventing a figure from nothing is worse than admitting there isn't one.
     */
    val safeToSpendPaise: Long?
        get() {
            val budget = budgetLeftPaise
            val capacity = afterCommitmentsPaise
            val lower = when {
                budget != null && capacity != null -> minOf(budget, capacity)
                budget != null -> budget
                capacity != null -> capacity
                else -> return null
            }
            return lower.coerceAtLeast(0L)
        }

    /** Which of the two is actually holding you back. */
    val limit: Limit
        get() {
            val budget = budgetLeftPaise
            val capacity = afterCommitmentsPaise
            return when {
                // Ties go to the budget: it is the one the household chose, and it is
                // the one they can do something about.
                budget != null && capacity != null ->
                    if (budget <= capacity) Limit.BUDGET else Limit.BALANCE
                budget != null -> Limit.BUDGET
                capacity != null -> Limit.BALANCE
                else -> Limit.NONE
            }
        }

    /**
     * How far the looser constraint runs past the binding one.
     *
     * The interesting number in the user's original question. ₹18,000 of budget left
     * against ₹10,149 in the bank means the budget is permitting ₹7,851 the household
     * cannot actually produce — which is exactly the thing neither screen could say.
     */
    val gapPaise: Long?
        get() {
            val budget = budgetLeftPaise ?: return null
            val capacity = afterCommitmentsPaise ?: return null
            return kotlin.math.abs(budget - capacity)
        }

    /** True when the budget allows more than the accounts can cover. */
    val budgetOutrunsMoney: Boolean
        get() = limit == Limit.BALANCE && !overBudget

    enum class Limit { BUDGET, BALANCE, NONE }
}

/**
 * Builds the figure from the pieces each screen already has.
 *
 * A function rather than a constructor call because the two nullable rules — usable is
 * unknown when *no* account has a figure, and unknown accounts are counted rather than
 * treated as empty — are the easy things to get wrong, and getting them wrong turns a
 * missing balance into a confident ₹0.
 */
fun affordability(
    budgetPaise: Long?,
    householdSpentPaise: Long,
    balances: List<AccountBalance>,
    committedRemainingPaise: Long = 0L,
    partialView: Boolean = false,
): Affordability {
    // Cards are debt, not capacity, and must be dropped before anything is summed.
    //
    // The Accounts panel partitions them out; this did not, and both call sites hand it
    // the unpartitioned list — so a ₹4,200 card balance was added to what the household
    // could spend, with the sign inverted. `AccountBalance.isCard` says outright that it
    // is "never summed with the others"; only the screen was honouring that.
    // Money put aside is excluded for the same reason, from the other direction. A fixed
    // deposit is money the household genuinely owns, so it is not a debt — but it cannot be
    // spent this month, and "safe to spend" is the one figure that has to mean available
    // rather than owned. Counting a ₹20,000 FD here would tell somebody they could spend it.
    val spendable = balances.filter { !it.isCard && !it.isSavings }
    val known = spendable.mapNotNull { it.usablePaise }
    return Affordability(
        // A zero or negative budget is no budget. Treating ₹0 as a cap would put every
        // household that has not set one permanently, uselessly, over budget.
        budgetPaise = budgetPaise?.takeIf { it > 0L },
        spentPaise = householdSpentPaise,
        usablePaise = if (known.isEmpty()) null else known.sum(),
        unknownAccounts = spendable.count { it.usablePaise == null },
        committedPaise = committedRemainingPaise.coerceAtLeast(0L),
        partialView = partialView,
    )
}
