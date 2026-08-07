package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.CASH_ACCOUNT
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * Attributing a payment after the fact, which nothing could do.
 *
 * The add sheet asks which account a payment came from, but the entry screen only ever
 * *displayed* the answer, and only when a bank had supplied it — so a hand-added payment
 * showed nothing there and could never be told. That is worse than an unlabelled row:
 * `accountBalances()` opens by discarding everything with neither a tail nor a bank, so
 * an unattributed payment is missing from the Accounts tab altogether.
 */
class PaidFromEntryTest {

    private fun payment(tail: String?, bank: String?) = Transaction(
        id = "t1",
        amountPaise = 450_75,
        type = TxnType.DEBIT,
        merchant = "Keecheril St",
        category = Category.FOOD,
        occurredAt = 2_000L,
        accountTail = tail,
        bank = bank,
        ownerUid = "manuel",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.MANUAL,
    )

    /** The defect, stated as a fact about the aggregator rather than about the screen. */
    @Test
    fun `an unattributed payment is invisible to the Accounts tab`() {
        val accounts = MonthlyAggregator.accountBalances(listOf(payment(null, null)))
        assertThat(accounts).isEmpty()
    }

    /** And attributing it is what puts the account on the screen. */
    @Test
    fun `naming the account makes it appear`() {
        val accounts = MonthlyAggregator.accountBalances(
            listOf(payment("3062", "Kerala Gramin Bank"))
        )
        assertThat(accounts.map { it.key }).containsExactly("3062")
    }

    /**
     * Cash is a real answer, not a missing one. It has no digits, so it is keyed by name —
     * which is exactly the case `accountBalances()` keeps a bank-only branch for.
     */
    @Test
    fun `cash is an account like any other`() {
        val accounts = MonthlyAggregator.accountBalances(listOf(payment(null, CASH_ACCOUNT)))
        assertThat(accounts.map { it.key }).containsExactly(CASH_ACCOUNT)
    }

    /**
     * Attribution must never move a balance.
     *
     * Balances here are quoted, never derived — the app never sees a cash deposit or a
     * silent interest credit, so a figure built by adding up transactions would drift and
     * never find its way back. Saying which account a payment came from answers *which*,
     * not *how much*.
     */
    @Test
    fun `attributing a payment does not derive a balance for the account`() {
        val accounts = MonthlyAggregator.accountBalances(
            listOf(payment("3062", "Kerala Gramin Bank"))
        )
        assertThat(accounts.single().balancePaise).isNull()
        assertThat(accounts.single().usablePaise).isNull()
    }
}
