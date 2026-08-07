package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * Linking a refund has to be undoable, and two refunds against one purchase have to add up.
 *
 * Both defects were found by asking what happens if somebody taps "This is a refund" by
 * mistake — which is exactly the question the feature's own comment says it was built for:
 * *"a claim you cannot withdraw is one people will not make"*.
 */
class RefundLinkTest {

    private fun debit(rupees: Long, refunded: Long = 0, id: String = "d1") = Transaction(
        id = id,
        amountPaise = rupees * 100,
        type = TxnType.DEBIT,
        merchant = "Amazon",
        category = Category.SHOPPING,
        occurredAt = 1_000L,
        ownerUid = "manuel",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
        refundedPaise = refunded * 100,
    )

    private fun credit(
        rupees: Long,
        id: String,
        refunds: String? = null,
        category: Category = Category.INCOME,
    ) = Transaction(
        id = id,
        amountPaise = rupees * 100,
        type = TxnType.CREDIT,
        merchant = "Amazon",
        category = category,
        occurredAt = 2_000L,
        ownerUid = "manuel",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
        refundsTxnId = refunds,
    )

    // ── 1 · a linked credit is out of income whatever its category says ──────

    /**
     * This is what makes rewriting the category unnecessary, and so what makes the undo
     * lossless. `totalReceived` requires `refundsTxnId == null`, so the link alone is
     * enough — the category never had to be touched.
     */
    @Test
    fun `a linked credit is not income even though its category still says Income`() {
        val ledger = listOf(debit(2_000), credit(2_000, id = "c1", refunds = "d1"))
        assertThat(MonthlyAggregator.totalReceived(ledger)).isEqualTo(0)
    }

    /** And an ordinary credit still is income, so the rule did not simply delete income. */
    @Test
    fun `an unlinked credit is still income`() {
        val ledger = listOf(debit(2_000), credit(2_000, id = "c1"))
        assertThat(MonthlyAggregator.totalReceived(ledger)).isEqualTo(2_000_00)
    }

    /**
     * A credit the household had hand-categorised. Unlinking used to write INCOME over
     * this unconditionally, because nothing recorded what it had been — so linking and
     * unlinking replaced somebody's correction with a guess.
     */
    @Test
    fun `a hand-categorised credit keeps its category through a link`() {
        val ledger = listOf(
            debit(2_000),
            credit(2_000, id = "c1", refunds = "d1", category = Category.OTHER),
        )
        // Still out of income on the strength of the link alone.
        assertThat(MonthlyAggregator.totalReceived(ledger)).isEqualTo(0)
        // And the category the household chose is still the category it has.
        assertThat(ledger.last().category).isEqualTo(Category.OTHER)
    }

    // ── 2 · refunds against one purchase accumulate ──────────────────────────

    /**
     * The arithmetic `recomputeRefunded` performs, stated on its own.
     *
     * `linkRefund` used to *assign* `refundedPaise`, so ₹2,000 then ₹3,000 against a
     * ₹5,000 order recorded ₹3,000 — leaving ₹2,000 of spending on the books that had
     * been given back. `observeRefundCandidates` keeps offering a partly-refunded
     * purchase, so the app actively invites the second link.
     */
    @Test
    fun `two refunds against one purchase add up`() {
        val purchase = debit(5_000)
        val refunds = listOf(credit(2_000, "c1", refunds = "d1"), credit(3_000, "c2", refunds = "d1"))
        val total = refunds.sumOf { it.amountPaise }.coerceIn(0, purchase.amountPaise)
        assertThat(total).isEqualTo(5_000_00)

        // And the purchase then costs nothing.
        assertThat(MonthlyAggregator.netSpent(purchase.copy(refundedPaise = total))).isEqualTo(0)
    }

    /** Removing one of two refunds leaves the other's share, not zero. */
    @Test
    fun `unlinking one refund keeps the other's share`() {
        val purchase = debit(5_000)
        val remaining = listOf(credit(3_000, "c2", refunds = "d1"))
        val total = remaining.sumOf { it.amountPaise }.coerceIn(0, purchase.amountPaise)
        assertThat(total).isEqualTo(3_000_00)
        assertThat(MonthlyAggregator.netSpent(purchase.copy(refundedPaise = total)))
            .isEqualTo(2_000_00)
    }

    /**
     * Still capped at the purchase. Uncapped, an over-refund would make the month's
     * spending negative — which is the reason the cap existed in the first place.
     */
    @Test
    fun `refunds beyond the purchase are capped`() {
        val purchase = debit(5_000)
        val refunds = listOf(credit(4_000, "c1", refunds = "d1"), credit(4_000, "c2", refunds = "d1"))
        val total = refunds.sumOf { it.amountPaise }.coerceIn(0, purchase.amountPaise)
        assertThat(total).isEqualTo(5_000_00)
        assertThat(MonthlyAggregator.netSpent(purchase.copy(refundedPaise = total))).isEqualTo(0)
    }

    /** A fully refunded purchase is not spending, which is the point of all of this. */
    @Test
    fun `a fully refunded purchase costs nothing`() {
        assertThat(MonthlyAggregator.totalSpent(listOf(debit(2_000, refunded = 2_000))))
            .isEqualTo(0)
    }
}
