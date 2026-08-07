package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.affordability
import com.manuel.ours.domain.model.AccountOwner
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.ManualBalance
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * An account's owner is something the household said, not something the ledger implies.
 *
 * The Accounts panel groups by this, so the difference is visible: get it from the last
 * payment out of the account and a joint account files itself under whoever used it most
 * recently and moves the next time the other person pays for something, while an account
 * added by hand — the partner's SBI, which has a typed balance and no transactions at
 * all — groups under a blank heading.
 */
class AccountOwnerTest {

    private fun debit(
        tail: String,
        ownerUid: String,
        ownerName: String,
        at: Long,
        bank: String = "Kerala Gramin Bank",
    ) = Transaction(
        id = "$tail$at",
        amountPaise = 100_00,
        type = TxnType.DEBIT,
        merchant = "Shop",
        category = Category.SHOPPING,
        occurredAt = at,
        accountTail = tail,
        bank = bank,
        ownerUid = ownerUid,
        ownerName = ownerName,
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
        balancePaise = 9_649_00,
    )

    // ── the owner is recorded, or it is nobody ───────────────────────────────

    /** Nothing recorded is Shared — an honest blank, not the most recent payer. */
    @Test
    fun `an account nobody has claimed has no owner`() {
        val result = MonthlyAggregator.accountBalances(
            transactions = listOf(debit("3062", "manuel", "Manuel", 2_000L)),
        )
        assertThat(result.single().ownerUid).isNull()
        assertThat(result.single().ownerName).isEmpty()
    }

    /**
     * The joint-account flip. Beula pays last, so the old rule handed her the account;
     * Manuel pays tomorrow and it becomes his. Recorded ownership does not move.
     */
    @Test
    fun `a joint account does not change hands when the other person pays`() {
        val owners = mapOf("3062" to AccountOwner("manuel", "Manuel"))
        val beulaPaidLast = listOf(
            debit("3062", "manuel", "Manuel", 1_000L),
            debit("3062", "beula", "Beula", 9_000L),
        )
        val result = MonthlyAggregator.accountBalances(beulaPaidLast, owners = owners)
        assertThat(result.single().ownerUid).isEqualTo("manuel")
        assertThat(result.single().ownerName).isEqualTo("Manuel")
    }

    /**
     * The partner's SBI: a typed balance and no transactions. Under the old rule there
     * was no `latest` row to read a name off, so it grouped under an empty heading — the
     * one account on this ledger whose ownership the household actually asked about.
     */
    @Test
    fun `an account with a typed balance and no payments still has its owner`() {
        val result = MonthlyAggregator.accountBalances(
            transactions = emptyList(),
            manual = mapOf(
                "SBI" to ManualBalance(
                    paise = 10_314_54,
                    setAt = 5_000L,
                    bank = "State Bank of India",
                    ownerUid = "manuel",
                )
            ),
            owners = mapOf("SBI" to AccountOwner("beula", "Beula")),
        )
        val sbi = result.single()
        assertThat(sbi.ownerUid).isEqualTo("beula")
        assertThat(sbi.ownerName).isEqualTo("Beula")
        // Who typed the figure is a different question from whose account it is, and the
        // two answers here are deliberately different people.
        assertThat(sbi.balancePaise).isEqualTo(10_314_54)
    }

    // ── grouping must not move a single rupee ────────────────────────────────

    /**
     * The rule the whole feature is subordinate to: the budget is one cap over one
     * household. Per-person sub-totals are presentation, so the household figure they
     * add up to is the same one that existed before anybody was named.
     */
    @Test
    fun `naming owners does not change what the household can spend`() {
        val txns = listOf(
            debit("3062", "manuel", "Manuel", 2_000L),
            debit("4657", "manuel", "Manuel", 3_000L, bank = "Federal Bank"),
        )
        val manual = mapOf(
            "SBI" to ManualBalance(10_314_54, 5_000L, "State Bank of India", "manuel")
        )

        val unclaimed = MonthlyAggregator.accountBalances(txns, manual = manual)
        val claimed = MonthlyAggregator.accountBalances(
            txns,
            manual = manual,
            owners = mapOf(
                "3062" to AccountOwner("manuel", "Manuel"),
                "SBI" to AccountOwner("beula", "Beula"),
            ),
        )

        // The per-account figures, and so every sub-total drawn from them.
        assertThat(claimed.map { it.usablePaise }).isEqualTo(unclaimed.map { it.usablePaise })

        // And the figure the app actually spends against.
        fun spendable(list: List<com.manuel.ours.domain.model.AccountBalance>) =
            affordability(
                budgetPaise = 38_500_00,
                householdSpentPaise = 0,
                balances = list,
            ).usablePaise
        assertThat(spendable(claimed)).isEqualTo(spendable(unclaimed))
    }

    /**
     * A partner's account counts towards what the household can spend. Decided, 7 Aug 2026.
     *
     * The panel had always behaved this way without saying so, and grouping by person is
     * what finally made it visible — "₹10,314 · Beula", sitting inside a figure headed
     * *safe to spend*. Asked directly, the household confirmed it: her money is household
     * money, and her spending from it personally does not change that.
     *
     * Worth a test precisely because the code looks the same either way. The alternative
     * answer would have made this account a savings-like exclusion and turned every
     * transfer to it into money leaving the pot rather than moving within it — and
     * nothing in the source would have flagged the change of mind.
     */
    @Test
    fun `a partner's account counts towards what the household can spend`() {
        val balances = MonthlyAggregator.accountBalances(
            transactions = listOf(debit("3062", "manuel", "Manuel", 2_000L)),
            manual = mapOf(
                "SBI" to ManualBalance(10_314_54, 5_000L, "State Bank of India", "manuel")
            ),
            owners = mapOf(
                "3062" to AccountOwner("manuel", "Manuel"),
                "SBI" to AccountOwner("beula", "Beula"),
            ),
        )
        val result = affordability(
            budgetPaise = 38_500_00,
            householdSpentPaise = 0,
            balances = balances,
        )
        // Both accounts, not just the one belonging to whoever is holding the phone.
        assertThat(result.usablePaise).isEqualTo(9_649_00 + 10_314_54)
    }

    /** Sub-totals add up to the household total, which is what makes the panel readable. */
    @Test
    fun `per-person subtotals sum to the household total`() {
        val balances = MonthlyAggregator.accountBalances(
            transactions = listOf(
                debit("3062", "manuel", "Manuel", 2_000L),
                debit("4657", "beula", "Beula", 3_000L, bank = "Federal Bank"),
            ),
            owners = mapOf(
                "3062" to AccountOwner("manuel", "Manuel"),
                "4657" to AccountOwner("beula", "Beula"),
            ),
        )
        val household = balances.mapNotNull { it.usablePaise }.sum()
        val bySubtotal = balances
            .groupBy { it.ownerUid }
            .values
            .sumOf { group -> group.mapNotNull { it.usablePaise }.sum() }
        assertThat(bySubtotal).isEqualTo(household)
    }
}
