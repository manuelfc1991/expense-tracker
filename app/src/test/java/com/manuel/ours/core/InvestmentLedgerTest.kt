package com.manuel.ours.core

import com.manuel.ours.domain.InvestmentLedger
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InvestmentLedgerTest {

    private var seq = 0

    private fun invest(rupees: Long) = txn(rupees, TxnType.DEBIT)
    private fun withdraw(rupees: Long) = txn(rupees, TxnType.CREDIT)

    private fun txn(rupees: Long, type: TxnType) = Transaction(
        id = "txn-${seq++}",
        amountPaise = rupees * 100,
        type = type,
        merchant = "Groww",
        category = Category.INVESTMENTS,
        occurredAt = 1_785_000_000_000L,
        ownerUid = "me",
        ownerName = "Me",
    )

    @Test
    fun `money going in is invested, not spent`() {
        val p = InvestmentLedger.position(listOf(invest(100_000)))
        assertThat(p.investedPaise).isEqualTo(1_00_000 * 100)
        assertThat(p.realisedGainPaise).isEqualTo(0)
    }

    @Test
    fun `a partial withdrawal returns principal, not profit`() {
        // ₹1,00,000 in, ₹40,000 out. You are not up ₹40,000 — you are still ₹60,000 in.
        val p = InvestmentLedger.position(listOf(invest(100_000), withdraw(40_000)))
        assertThat(p.investedPaise).isEqualTo(60_000 * 100)
        assertThat(p.realisedGainPaise).isEqualTo(0)
    }

    @Test
    fun `withdrawing more than you put in realises a gain`() {
        // ₹1,00,000 in, ₹1,20,000 out -> ₹20,000 of genuine profit.
        val p = InvestmentLedger.position(listOf(invest(100_000), withdraw(120_000)))
        assertThat(p.investedPaise).isEqualTo(0)
        assertThat(p.realisedGainPaise).isEqualTo(20_000 * 100)
        assertThat(p.hasExited).isTrue()
    }

    @Test
    fun `a single withdrawal splits into principal and gain`() {
        val split = InvestmentLedger.splitWithdrawal(
            withdrawalPaise = 1_20_000 * 100,
            investedBeforePaise = 1_00_000 * 100,
        )
        assertThat(split.principalPaise).isEqualTo(1_00_000 * 100)
        assertThat(split.gainPaise).isEqualTo(20_000 * 100)
    }

    @Test
    fun `a withdrawal within principal has no gain component`() {
        val split = InvestmentLedger.splitWithdrawal(
            withdrawalPaise = 30_000 * 100,
            investedBeforePaise = 1_00_000 * 100,
        )
        assertThat(split.gainPaise).isEqualTo(0)
        assertThat(split.principalPaise).isEqualTo(30_000 * 100)
    }

    @Test
    fun `many deposits and withdrawals net out correctly`() {
        val p = InvestmentLedger.position(
            listOf(
                invest(50_000), invest(30_000), invest(20_000), // ₹1,00,000 in
                withdraw(60_000), withdraw(70_000),             // ₹1,30,000 out
            )
        )
        assertThat(p.depositsPaise).isEqualTo(1_00_000 * 100)
        assertThat(p.withdrawalsPaise).isEqualTo(1_30_000 * 100)
        assertThat(p.realisedGainPaise).isEqualTo(30_000 * 100)
        assertThat(p.investedPaise).isEqualTo(0)
    }

    @Test
    fun `a loss is not guessed while the position may still be open`() {
        // ₹1,00,000 in, ₹90,000 out. That is indistinguishable from a partial
        // withdrawal, so claiming a ₹10,000 loss would be fabricating information.
        val p = InvestmentLedger.position(listOf(invest(100_000), withdraw(90_000)))
        assertThat(InvestmentLedger.realisedLoss(p, positionClosed = false)).isEqualTo(0)
        assertThat(p.investedPaise).isEqualTo(10_000 * 100)
    }

    @Test
    fun `a loss is reported once the position is declared closed`() {
        val p = InvestmentLedger.position(listOf(invest(100_000), withdraw(90_000)))
        assertThat(InvestmentLedger.realisedLoss(p, positionClosed = true))
            .isEqualTo(10_000 * 100)
    }

    @Test
    fun `growth while still invested is invisible, by design`() {
        // The app sees bank cash only. A fund that doubled shows nothing until money
        // actually comes back — anything else would be invented.
        val p = InvestmentLedger.position(listOf(invest(100_000)))
        assertThat(p.realisedGainPaise).isEqualTo(0)
        assertThat(p.investedPaise).isEqualTo(1_00_000 * 100)
    }
}
