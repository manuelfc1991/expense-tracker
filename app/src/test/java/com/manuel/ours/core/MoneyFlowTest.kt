package com.manuel.ours.core

import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.MoneyFlow
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Spending, saving and moving are three different things. Collapsing them into one
 * "money out" number is what makes an expense tracker tell you that you overspent in
 * the month you saved the most.
 */
class MoneyFlowTest {

    private val parser = SmsParser()
    private val now = 1_785_000_000_000L

    private fun txn(
        amount: Long,
        category: Category,
        type: TxnType = TxnType.DEBIT,
    ) = Transaction(
        id = "$category-$amount-$type",
        amountPaise = amount,
        type = type,
        merchant = "Test",
        category = category,
        occurredAt = now,
        ownerUid = "me",
        ownerName = "Me",
    )

    private fun expense(sender: String, body: String) =
        (parser.parse(sender, body, now) as SmsParser.Result.Expense).txn

    // ------------------------------------------------------------------ flow model

    @Test
    fun `only spending categories count as spending`() {
        assertThat(Category.FOOD.countsAsSpending).isTrue()
        assertThat(Category.RENT.countsAsSpending).isTrue()
        // An EMI genuinely leaves your hands — that is real spending.
        assertThat(Category.EMI.countsAsSpending).isTrue()

        // Money that left the household counts, even when the bank named no payee
        // and even when it arrived as a card bill — on a real ledger not one of 460
        // rows was an individual card purchase, so the bill is the only record of it.
        assertThat(Category.TRANSFERS.countsAsSpending).isTrue()
        assertThat(Category.CARD_PAYMENT.countsAsSpending).isTrue()

        // Saving is the one debit that is still yours afterwards.
        assertThat(Category.INVESTMENTS.countsAsSpending).isFalse()
    }

    @Test
    fun `an FD is saved, not spent`() {
        val txns = listOf(
            txn(50_000, Category.FOOD),
            txn(1_00_00_000, Category.INVESTMENTS), // ₹1,00,000 into an FD
        )
        assertThat(MonthlyAggregator.totalSpent(txns)).isEqualTo(50_000)
        assertThat(MonthlyAggregator.totalSaved(txns)).isEqualTo(1_00_00_000)
        // The raw bank view still sees both leave the account.
        assertThat(MonthlyAggregator.totalDebited(txns)).isEqualTo(1_00_50_000)
    }

    @Test
    fun `salary is income and never touches the spending total`() {
        val txns = listOf(
            txn(85_00_000, Category.INCOME, TxnType.CREDIT),
            txn(50_000, Category.FOOD),
        )
        assertThat(MonthlyAggregator.totalReceived(txns)).isEqualTo(85_00_000)
        assertThat(MonthlyAggregator.totalSpent(txns)).isEqualTo(50_000)
    }

    @Test
    fun `a matured deposit is not income`() {
        val txns = listOf(
            txn(85_00_000, Category.INCOME, TxnType.CREDIT),
            txn(1_00_00_000, Category.INVESTMENTS, TxnType.CREDIT), // FD matured
        )
        // Counting the maturity as income would report ₹1,85,000 earned this month
        // and a wildly wrong savings rate.
        assertThat(MonthlyAggregator.totalReceived(txns)).isEqualTo(85_00_000)
    }

    @Test
    fun `charts and headline always agree`() {
        val txns = listOf(
            txn(30_000, Category.FOOD),
            txn(20_000, Category.TRANSPORT),
            txn(5_00_000, Category.INVESTMENTS),
            txn(2_00_000, Category.CARD_PAYMENT),
            txn(10_00_000, Category.TRANSFERS),
        )
        val headline = MonthlyAggregator.totalSpent(txns)
        val donut = MonthlyAggregator.byCategory(txns).sumOf { it.totalPaise }
        val bars = MonthlyAggregator.byDay(txns, 2026, 7).sumOf { it.totalPaise }
        val members = MonthlyAggregator.byMember(txns).sumOf { it.totalPaise }

        assertThat(donut).isEqualTo(headline)
        assertThat(bars).isEqualTo(headline)
        assertThat(members).isEqualTo(headline)
        // Food + transport + card bill + transfer. Only the investment is held back.
        assertThat(headline).isEqualTo(12_50_000)
    }

    @Test
    fun `excluded amounts are reported, not silently dropped`() {
        val txns = listOf(
            txn(50_000, Category.FOOD),
            txn(5_00_000, Category.INVESTMENTS),
            txn(2_00_000, Category.CARD_PAYMENT),
        )
        val summary = MonthlyAggregator.summarize(2026, 7, txns, emptyList())

        assertThat(summary.totalSpentPaise).isEqualTo(2_50_000)
        assertThat(summary.excludedPaise).isEqualTo(5_00_000)
        // Every rupee debited is accounted for somewhere the user can see.
        assertThat(summary.totalSpentPaise + summary.excludedPaise)
            .isEqualTo(MonthlyAggregator.totalDebited(txns))
    }

    // ----------------------------------------------------------- parser detection

    @Test
    fun `booking a fixed deposit is detected as saving`() {
        val t = expense(
            "AD-FEDBNK",
            "Rs 100000.00 debited from a/c XX4657 on 12Jul26 towards Fixed Deposit " +
                "a/c XX9911. Bal Rs 25000.00 -Federal Bank",
        )
        assertThat(t.kind).isEqualTo(SmsParser.Kind.SAVINGS_DEPOSIT)
    }

    @Test
    fun `an SIP instalment is saving, not shopping`() {
        val t = expense(
            "AD-HDFCBK",
            "Rs.5,000.00 debited from a/c XX1234 towards SIP installment on 05-Jul-26.",
        )
        assertThat(t.kind).isEqualTo(SmsParser.Kind.SAVINGS_DEPOSIT)
    }

    @Test
    fun `a recurring deposit is saving`() {
        val t = expense(
            "AD-FEDBNK",
            "Rs 2000 debited from a/c XX4657 towards Recurring Deposit a/c XX0165 " +
                "on 10Jul26. Bal Rs 5000.00 -Federal Bank",
        )
        assertThat(t.kind).isEqualTo(SmsParser.Kind.SAVINGS_DEPOSIT)
    }

    @Test
    fun `every category declares a flow`() {
        // A new category added without a flow silently defaults to SPENDING, which is
        // the safe direction but worth making a conscious choice.
        assertThat(Category.entries.map { it.flow }).doesNotContain(null)
        assertThat(Category.INCOME.flow).isEqualTo(MoneyFlow.INCOMING)
        assertThat(Category.INVESTMENTS.flow).isEqualTo(MoneyFlow.SAVING)
    }
}
