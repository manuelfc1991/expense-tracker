package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.AccountBalance
import com.manuel.ours.domain.model.CASH_ACCOUNT
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.PaidFrom
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import com.manuel.ours.domain.model.shortLabel
import org.junit.Test

/**
 * A payment you typed in yourself still came out of somewhere.
 *
 * `addManual` set neither `accountTail` nor `bank`, and `accountBalances()` opens by
 * discarding every row that has neither. So a hand-added payment was not merely
 * unattributed — it was invisible to the Accounts tab entirely. The sheet that exists
 * precisely for payments no bank messaged about was the one place nothing filled the
 * account in.
 */
class PaidFromTest {

    private fun manual(
        rupees: Long,
        accountTail: String? = null,
        bank: String? = null,
    ) = Transaction(
        id = "m$rupees$bank",
        amountPaise = rupees * 100,
        type = TxnType.DEBIT,
        merchant = "Chaayos",
        category = Category.FOOD,
        occurredAt = 1_000L,
        accountTail = accountTail,
        bank = bank,
        ownerUid = "me",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.MANUAL,
    )

    // ─── The three answers ───────────────────────────────────────────────────

    @Test
    fun `cash is stored as a place, not as a blank`() {
        assertThat(PaidFrom.Cash.bank).isEqualTo(CASH_ACCOUNT)
        assertThat(PaidFrom.Cash.accountTail).isNull()
        // And that is what makes it visible: accountBalances keeps a row that names a bank.
        val accounts = MonthlyAggregator.accountBalances(listOf(manual(120, bank = CASH_ACCOUNT)))
        assertThat(accounts.map { it.key }).containsExactly(CASH_ACCOUNT)
    }

    @Test
    fun `an account choice carries the tail the Accounts tab groups on`() {
        val option = PaidFrom.Account(accountTail = "3062", bank = "Kerala Gramin Bank")
        val accounts = MonthlyAggregator.accountBalances(
            listOf(manual(500, option.accountTail, option.bank))
        )
        assertThat(accounts.map { it.key }).containsExactly("3062")
        assertThat(accounts.single().bank).isEqualTo("Kerala Gramin Bank")
    }

    /**
     * "Not sure" is a real answer and must not be dressed up as one of the others.
     *
     * It stores nothing, so the row stays out of the Accounts tab — which is the old
     * behaviour, now reached only when somebody chooses it rather than by default.
     */
    @Test
    fun `not sure stores nothing and is not guessed at`() {
        assertThat(PaidFrom.Unknown.bank).isNull()
        assertThat(PaidFrom.Unknown.accountTail).isNull()
        assertThat(MonthlyAggregator.accountBalances(listOf(manual(90)))).isEmpty()
    }

    // ─── The defect it fixes ─────────────────────────────────────────────────

    @Test
    fun `a hand-added payment used to vanish from Accounts, and no longer has to`() {
        val unattributed = MonthlyAggregator.accountBalances(listOf(manual(300)))
        assertThat(unattributed).isEmpty()

        val attributed = MonthlyAggregator.accountBalances(
            listOf(manual(300, bank = CASH_ACCOUNT))
        )
        assertThat(attributed).hasSize(1)
    }

    /** Attribution is not arithmetic: choosing an account must not invent a balance. */
    @Test
    fun `naming an account does not give it a balance`() {
        val accounts = MonthlyAggregator.accountBalances(
            listOf(manual(300, accountTail = "3062", bank = "Kerala Gramin Bank"))
        )
        // The bank has quoted nothing, so there is nothing to report — never a derived zero.
        assertThat(accounts.single().balancePaise).isNull()
    }

    // ─── The chip label ──────────────────────────────────────────────────────

    @Test
    fun `the chip names the bank and the tail that tells two apart`() {
        fun account(tail: String?, bank: String?) = AccountBalance(
            key = tail ?: bank.orEmpty(),
            accountTail = tail,
            bank = bank,
            balancePaise = null,
            asOf = null,
            source = null,
            ownerName = "Manuel",
        )
        assertThat(account("3062", "Kerala Gramin Bank").shortLabel())
            .isEqualTo("Kerala Gramin Bank ···3062")
        // A wallet has no number, and inventing one would be worse than leaving it off.
        assertThat(account(null, CASH_ACCOUNT).shortLabel()).isEqualTo("Cash")
    }
}
