package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.MoneyFlow
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * Sorting a payment as "Moving money" has to take it out of spending.
 *
 * It did not. A debit the bank named no payee for arrives as [Category.TRANSFERS], and
 * the Sort screen's "Moving money" chip assigned [Category.TRANSFERS] — the category the
 * row was already in. The tap was a no-op: same category, same total, and the payment
 * reappeared next time. The screen meanwhile said the row was "held out of spending
 * until you decide", while Transfers counts as spending the whole time.
 *
 * Which of the two halves to change was not obvious. Transfers counting is deliberate
 * and evidenced — 83 of 85 unnamed-payee rows on the real ledger were money sent to
 * other people — so the default is right and the *override* was wrong.
 */
class MovingMoneyTest {

    private fun debit(rupees: Long, category: Category) = Transaction(
        id = "t$rupees$category",
        amountPaise = rupees * 100,
        type = TxnType.DEBIT,
        merchant = "",
        category = category,
        occurredAt = 0L,
        ownerUid = "me",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
    )

    @Test
    fun `the category the Moving money chip assigns is not counted as spending`() {
        // The chip writes SELF_TRANSFER. If that ever counts, the chip is a no-op again.
        assertThat(Category.SELF_TRANSFER.countsAsSpending).isFalse()
        assertThat(Category.SELF_TRANSFER.flow).isEqualTo(MoneyFlow.NEUTRAL)

        val ledger = listOf(debit(2_000, Category.SELF_TRANSFER))
        assertThat(MonthlyAggregator.totalSpent(ledger)).isEqualTo(0)
        // It still left the account, and that figure must not move.
        assertThat(MonthlyAggregator.totalDebited(ledger)).isEqualTo(200_000)
    }

    /**
     * The default for an unnamed payee, left alone on purpose.
     *
     * Guessing that a payment to nobody-in-particular was your own account is wrong four
     * times in five, so an untouched row belongs in the total. This is the assertion that
     * stops anyone "fixing" the chip by making Transfers neutral instead.
     */
    @Test
    fun `an unsorted transfer is still counted, which is why the chip has to do something`() {
        assertThat(Category.TRANSFERS.countsAsSpending).isTrue()
        assertThat(MonthlyAggregator.totalSpent(listOf(debit(2_000, Category.TRANSFERS))))
            .isEqualTo(200_000)
    }

    /** Sorting one row as Moving money takes exactly that row out of the total. */
    @Test
    fun `sorting a payment as Moving money changes the headline`() {
        val before = listOf(debit(500, Category.FOOD), debit(2_000, Category.TRANSFERS))
        val after = listOf(debit(500, Category.FOOD), debit(2_000, Category.SELF_TRANSFER))

        assertThat(MonthlyAggregator.totalSpent(before)).isEqualTo(250_000)
        assertThat(MonthlyAggregator.totalSpent(after)).isEqualTo(50_000)
    }

    /**
     * What the money model actually excludes, pinned against the documentation.
     *
     * The comment on `NON_SPEND` listed six categories as kept out of spending when the
     * code has only ever kept out three — and the two it wrongly named, Transfers and
     * Card bill, are the two people most expect to be excluded.
     */
    @Test
    fun `exactly three categories are kept out of spending`() {
        val excluded = Category.entries.filter { !it.countsAsSpending }.map { it.label }.toSet()
        assertThat(excluded).containsExactly("Savings", "Ours", "Income")
    }
}
