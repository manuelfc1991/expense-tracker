package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.BalanceSource
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.ManualBalance
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * A balance somebody typed has to move when money moves.
 *
 * The real case, from this household's main account. ₹10,149 was typed on 5 August. On
 * 7 August ₹185 left ···3062 — and Kerala Gramin quotes a balance on some message shapes
 * and omits it on a UPI transfer, so no newer figure ever arrived. The panel went on
 * showing ₹10,149 with the payment sitting in the ledger, unapplied.
 *
 * A bank-quoted balance never needed this: the next message from that account corrects
 * it. A typed one is corrected by nothing, so it rots while looking authoritative.
 *
 * This is not a derived balance — the rule that the app must never total transactions
 * into a balance still holds, because it cannot see a cash deposit or a silent interest
 * credit. It only ever adjusts *forward* from a figure a person or a bank asserted.
 */
class TypedBalanceDriftTest {

    private val typedAt = 5_000_000L

    private fun txn(paise: Long, at: Long, type: TxnType = TxnType.DEBIT) = Transaction(
        id = "t$at",
        amountPaise = paise,
        type = type,
        merchant = "Payee",
        category = Category.TRANSFERS,
        occurredAt = at,
        accountTail = "3062",
        bank = "Kerala Gramin Bank",
        ownerUid = "manuel",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
    )

    private fun balances(rows: List<Transaction>) = MonthlyAggregator.accountBalances(
        transactions = rows,
        manual = mapOf(
            "3062" to ManualBalance(10_149_00, typedAt, "Kerala Gramin Bank", "manuel")
        ),
        minimums = mapOf("3062" to 500_00),
    ).single()

    /** The bug, in the household's own figures. */
    @Test
    fun `a payment after the typed figure comes off it`() {
        val account = balances(listOf(txn(185_00, typedAt + 1_000)))
        assertThat(account.balancePaise).isEqualTo(10_149_00 - 185_00)
        assertThat(account.movedSincePaise).isEqualTo(-185_00)
        // And the figure the panel shows, after the ₹500 the bank makes you keep.
        assertThat(account.usablePaise).isEqualTo(9_964_00 - 500_00)
    }

    /** A payment made *before* the figure was typed is already in it. */
    @Test
    fun `a payment before the typed figure is not subtracted twice`() {
        val account = balances(listOf(txn(185_00, typedAt - 1_000)))
        assertThat(account.balancePaise).isEqualTo(10_149_00)
        assertThat(account.movedSincePaise).isEqualTo(0)
    }

    /** Money arriving moves it the other way. */
    @Test
    fun `a credit after the typed figure is added`() {
        val account = balances(listOf(txn(58_200_00, typedAt + 1_000, TxnType.CREDIT)))
        assertThat(account.balancePaise).isEqualTo(10_149_00 + 58_200_00)
        assertThat(account.movedSincePaise).isEqualTo(58_200_00)
    }

    /** Several movements net off against each other. */
    @Test
    fun `debits and credits since are netted`() {
        val account = balances(
            listOf(
                txn(185_00, typedAt + 1_000),
                txn(1_000_00, typedAt + 2_000),
                txn(500_00, typedAt + 3_000, TxnType.CREDIT),
            )
        )
        assertThat(account.movedSincePaise).isEqualTo(-685_00)
        assertThat(account.balancePaise).isEqualTo(10_149_00 - 685_00)
    }

    /** It is still the household's figure, so it still reads as one. */
    @Test
    fun `an adjusted balance is still marked as hand-entered`() {
        assertThat(balances(listOf(txn(185_00, typedAt + 1))).source)
            .isEqualTo(BalanceSource.HAND)
    }

    // ── a bank-quoted balance is left alone ──────────────────────────────────

    /**
     * The important guard. A quoted balance corrects itself on the next message, so
     * adjusting it would double-count the very payment that produced the quote — the
     * message says "debited ₹185, balance now ₹9,964", and subtracting ₹185 again gives
     * ₹9,779, a figure no one has ever held.
     */
    @Test
    fun `a bank-quoted balance is never adjusted`() {
        val quoted = txn(185_00, typedAt + 10_000).copy(balancePaise = 9_964_00)
        val account = MonthlyAggregator.accountBalances(
            transactions = listOf(quoted),
            manual = mapOf(
                "3062" to ManualBalance(10_149_00, typedAt, "Kerala Gramin Bank", "manuel")
            ),
        ).single()

        assertThat(account.source).isEqualTo(BalanceSource.BANK)
        assertThat(account.balancePaise).isEqualTo(9_964_00)
        assertThat(account.movedSincePaise).isEqualTo(0)
    }

    /** An account nobody has typed a figure for is untouched by any of this. */
    @Test
    fun `an account with no typed figure is unaffected`() {
        val account = MonthlyAggregator.accountBalances(
            transactions = listOf(txn(185_00, typedAt + 1_000))
        ).single()
        assertThat(account.balancePaise).isNull()
        assertThat(account.movedSincePaise).isEqualTo(0)
    }

    /**
     * A typed zero is a real figure, and payments still move it. Zero has been mistaken
     * for "unknown" in this file before, which is why it is pinned here too.
     */
    @Test
    fun `a typed zero still moves`() {
        val account = MonthlyAggregator.accountBalances(
            transactions = listOf(txn(500_00, typedAt + 1_000, TxnType.CREDIT)),
            manual = mapOf("3062" to ManualBalance(0, typedAt, "Kerala Gramin Bank", "manuel")),
        ).single()
        assertThat(account.balancePaise).isEqualTo(500_00)
    }
}
