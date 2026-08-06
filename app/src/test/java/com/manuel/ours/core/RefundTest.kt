package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.MemberFilter
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * A refund cancels a purchase, and neither side is spending or income.
 *
 * `docs/REVIEW.md` §2: every credit that is not a maturing investment becomes Income, and spending
 * counts debits only. So a ₹2,000 return left the ledger holding a ₹2,000 debit **and** a ₹2,000
 * credit — net worth right, **spending overstated by ₹2,000**, and the budget charged for a
 * purchase that was undone. Near the cap the app told the household to stop when it need not.
 *
 * The defect was invisible precisely because net worth was correct, which is why it is worth a
 * test rather than a careful reading.
 */
class RefundTest {

    private var seq = 0

    private fun debit(
        rupees: Long,
        merchant: String = "Amazon",
        category: Category = Category.SHOPPING,
        refundedRupees: Long = 0,
        day: Int = 4,
    ) = Transaction(
        id = "d${seq++}",
        amountPaise = rupees * 100,
        type = TxnType.DEBIT,
        merchant = merchant,
        category = category,
        occurredAt = at(day),
        ownerUid = "me",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
        refundedPaise = refundedRupees * 100,
    )

    private fun credit(
        rupees: Long,
        merchant: String = "Amazon",
        category: Category = Category.INCOME,
        refunds: String? = null,
        day: Int = 7,
    ) = Transaction(
        id = "c${seq++}",
        amountPaise = rupees * 100,
        type = TxnType.CREDIT,
        merchant = merchant,
        category = category,
        occurredAt = at(day),
        ownerUid = "me",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
        refundsTxnId = refunds,
    )

    private fun at(day: Int): Long =
        OursZone.startOfDay(java.time.LocalDate.of(2026, 8, day)) + 10 * 3_600_000L

    // ─── The defect ──────────────────────────────────────────────────────────

    @Test
    fun `an unlinked return overstates spending and income, which is the bug`() {
        val ledger = listOf(debit(2_000), credit(2_000))
        // This is the old behaviour, asserted so the fix below is measured against something.
        assertThat(MonthlyAggregator.totalSpent(ledger)).isEqualTo(200_000)
        assertThat(MonthlyAggregator.totalReceived(ledger)).isEqualTo(200_000)
    }

    @Test
    fun `once linked, neither side counts`() {
        val purchase = debit(2_000, refundedRupees = 2_000)
        // linkRefund also moves the credit to SELF_TRANSFER, whose flow is NEUTRAL.
        val refund = credit(2_000, category = Category.SELF_TRANSFER, refunds = purchase.id)
        val ledger = listOf(purchase, refund)

        assertThat(MonthlyAggregator.totalSpent(ledger)).isEqualTo(0)
        assertThat(MonthlyAggregator.totalReceived(ledger)).isEqualTo(0)
    }

    @Test
    fun `a partial refund leaves the rest of the purchase counted`() {
        val purchase = debit(2_000, refundedRupees = 800)
        val refund = credit(800, category = Category.SELF_TRANSFER, refunds = purchase.id)
        val ledger = listOf(purchase, refund)

        // ₹1,200 of the order was kept.
        assertThat(MonthlyAggregator.totalSpent(ledger)).isEqualTo(120_000)
        assertThat(MonthlyAggregator.totalReceived(ledger)).isEqualTo(0)
    }

    /**
     * A credit still marked Income but carrying a link is excluded anyway.
     *
     * The category and the link are two fields and a person can change the first by hand after
     * linking. Belt and braces: money coming back is not income however the row is labelled.
     */
    @Test
    fun `a linked credit left as Income is still not income`() {
        val purchase = debit(2_000, refundedRupees = 2_000)
        val refund = credit(2_000, category = Category.INCOME, refunds = purchase.id)
        assertThat(MonthlyAggregator.totalReceived(listOf(purchase, refund))).isEqualTo(0)
    }

    // ─── The charts must agree with the headline ──────────────────────────────

    /**
     * The source promises every chart funnels through one place so they cannot disagree with the
     * headline. Netting the headline but not the charts would have broken exactly that promise.
     */
    @Test
    fun `every per-category, per-day and per-member figure nets the refund too`() {
        val purchase = debit(2_000, refundedRupees = 2_000)
        // A different category, so the Shopping assertion below is about the refund alone.
        val kept = debit(500, merchant = "Swiggy", category = Category.FOOD, day = 5)
        val refund = credit(2_000, category = Category.SELF_TRANSFER, refunds = purchase.id)
        val ledger = listOf(purchase, kept, refund)

        val headline = MonthlyAggregator.totalSpent(ledger)
        assertThat(headline).isEqualTo(50_000)

        val byCategory = MonthlyAggregator.byCategory(ledger).sumOf { it.totalPaise }
        assertThat(byCategory).isEqualTo(headline)

        val byDay = MonthlyAggregator.byDay(ledger, 2026, 8).sumOf { it.totalPaise }
        assertThat(byDay).isEqualTo(headline)

        val byMember = MonthlyAggregator.byMember(ledger).sumOf { it.totalPaise }
        assertThat(byMember).isEqualTo(headline)

        val topMerchants = MonthlyAggregator.topMerchants(ledger, limit = 10).sumOf { it.totalPaise }
        assertThat(topMerchants).isEqualTo(headline)

        // And the refunded purchase's own category reports zero rather than being dropped, so the
        // row is still visible in a filter with an honest figure against it.
        assertThat(MonthlyAggregator.spentInCategory(ledger, Category.SHOPPING)).isEqualTo(0)
    }

    // ─── Edges ───────────────────────────────────────────────────────────────

    @Test
    fun `a refund larger than its purchase cannot make spending negative`() {
        // The repository caps this on write, but the aggregator must not depend on that: a row
        // could arrive from the other phone written by a build that did not.
        val purchase = debit(2_000, refundedRupees = 5_000)
        assertThat(MonthlyAggregator.totalSpent(listOf(purchase))).isEqualTo(0)
        assertThat(MonthlyAggregator.netSpent(purchase)).isEqualTo(0)
    }

    @Test
    fun `left our accounts still counts the full debit`() {
        // The money did leave, and came back as a separate credit. "Left our accounts" answers a
        // different question from "spending" and must not be netted — that distinction is the
        // whole reason the two figures both exist.
        val purchase = debit(2_000, refundedRupees = 2_000)
        assertThat(MonthlyAggregator.totalDebited(listOf(purchase))).isEqualTo(200_000)
    }

    @Test
    fun `an ordinary salary is untouched`() {
        val salary = credit(58_200, merchant = "Kerala Gramin")
        assertThat(MonthlyAggregator.totalReceived(listOf(salary))).isEqualTo(5_820_000)
    }

    @Test
    fun `a refund survives the member filter like any other row`() {
        val purchase = debit(2_000, refundedRupees = 2_000)
        val filtered = MonthlyAggregator.applyFilter(
            listOf(purchase), MemberFilter.Person("me"), "me",
        )
        assertThat(MonthlyAggregator.totalSpent(filtered)).isEqualTo(0)
    }
}
