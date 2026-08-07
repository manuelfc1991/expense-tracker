package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.AccountOwner
import com.manuel.ours.domain.model.CardInfo
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.ManualBalance
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * Removing an account the household added by mistake.
 *
 * There was no way to. "Add an account" wrote a balance rule and nothing anywhere could
 * take it back, so a typo sat in "What is left" for good.
 *
 * The trap is that `accountBalances()` builds its key set from **four** sources — the
 * ledger, typed balances, minimums and cards — so clearing one and calling it done leaves
 * the account on screen. These tests are written against that key set for that reason.
 */
class RemoveAccountTest {

    private fun payment(tail: String) = Transaction(
        id = "t-$tail",
        amountPaise = 100_00,
        type = TxnType.DEBIT,
        merchant = "Shop",
        category = Category.SHOPPING,
        occurredAt = 2_000L,
        accountTail = tail,
        bank = "Kerala Gramin Bank",
        ownerUid = "manuel",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
    )

    /** Everything about the account cleared at once — the state after `removeAccount`. */
    @Test
    fun `an account with nothing left recorded disappears`() {
        val before = MonthlyAggregator.accountBalances(
            transactions = emptyList(),
            manual = mapOf("Typo" to ManualBalance(1_000_00, 5_000L, "Typo Bank", "manuel")),
            minimums = mapOf("Typo" to 500_00),
            cards = mapOf("Typo" to CardInfo(limitPaise = 10_000_00)),
            owners = mapOf("Typo" to AccountOwner("manuel", "Manuel")),
        )
        assertThat(before.map { it.key }).containsExactly("Typo")

        val after = MonthlyAggregator.accountBalances(transactions = emptyList())
        assertThat(after).isEmpty()
    }

    /**
     * The partial-removal trap, one source at a time. Each of these leaves the account
     * on the screen, which is why `removeAccount` tombstones all four rules rather than
     * only the balance.
     */
    @Test
    fun `clearing only the balance is not enough`() {
        fun keysWith(
            minimums: Map<String, Long> = emptyMap(),
            cards: Map<String, CardInfo> = emptyMap(),
        ) = MonthlyAggregator.accountBalances(
            transactions = emptyList(),
            manual = emptyMap(),
            minimums = minimums,
            cards = cards,
        ).map { it.key }

        assertThat(keysWith(minimums = mapOf("Typo" to 500_00))).containsExactly("Typo")
        assertThat(keysWith(cards = mapOf("Typo" to CardInfo()))).containsExactly("Typo")
    }

    // ── an account the ledger references cannot be removed ───────────────────

    /**
     * A payment out of ···3062 is evidence the account exists, and the aggregator rebuilds
     * it from the transactions on every read. Removing the rules cannot hide it — so the
     * screen must not offer to, which is what [fromLedger] is for.
     */
    @Test
    fun `an account with payments survives having its rules cleared`() {
        val after = MonthlyAggregator.accountBalances(transactions = listOf(payment("3062")))
        assertThat(after.map { it.key }).containsExactly("3062")
        assertThat(after.single().fromLedger).isTrue()
    }

    /** And a hand-added one is marked as removable. */
    @Test
    fun `a hand-added account is not from the ledger`() {
        val result = MonthlyAggregator.accountBalances(
            transactions = emptyList(),
            manual = mapOf("SBI" to ManualBalance(10_314_54, 5_000L, "State Bank of India", "manuel")),
        )
        assertThat(result.single().fromLedger).isFalse()
    }

    /**
     * Removing one account leaves the others alone — the obvious thing to get wrong when
     * a single write clears four rule types.
     */
    @Test
    fun `removing one account does not touch the rest`() {
        val after = MonthlyAggregator.accountBalances(
            transactions = listOf(payment("3062")),
            manual = mapOf("SBI" to ManualBalance(10_314_54, 5_000L, "State Bank of India", "manuel")),
        )
        assertThat(after.map { it.key }).containsExactly("3062", "SBI")
    }
}
