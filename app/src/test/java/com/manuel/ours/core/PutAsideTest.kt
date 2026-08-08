package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.affordability
import com.manuel.ours.domain.model.CardInfo
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.ManualBalance
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * Money the household owns and cannot spend — a fixed deposit, an RD, a PPF.
 *
 * The app had two answers for what a balance is: available, or owed. A deposit is neither.
 * Counting it in "what is left" tells somebody they can spend money that is locked up;
 * leaving it off the screen denies they own it. So it is a third kind, and the thing that
 * makes it a *kind* rather than a label is that it decides which total the figure joins.
 *
 * This is deliberately the same shape as [CardConversionTest], because it is the same bug
 * from the other direction — and the card version shipped for a whole release with only
 * the screen honouring the partition while `affordability` summed the lot.
 */
class PutAsideTest {

    private fun payment(tail: String, balance: Long?) = Transaction(
        id = "t-$tail",
        amountPaise = 1_000_00,
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
        balancePaise = balance,
    )

    private fun balances(savings: Set<String> = emptySet()) = MonthlyAggregator.accountBalances(
        transactions = listOf(payment("3062", 9_649_00), payment("7788", 20_000_00)),
        savings = savings,
    )

    @Test
    fun `an account the ledger discovered can be marked money put aside`() {
        assertThat(balances().none { it.isSavings }).isTrue()
        assertThat(balances(setOf("7788")).single { it.key == "7788" }.isSavings).isTrue()
    }

    /**
     * The figure that actually moves. ₹20,000 of deposit counted as spendable before, and
     * not after — while the current account beside it is untouched.
     */
    @Test
    fun `money put aside is not counted as safe to spend`() {
        fun usable(savings: Set<String>) = affordability(
            budgetPaise = 40_000_00,
            householdSpentPaise = 0,
            balances = balances(savings),
        ).usablePaise

        assertThat(usable(emptySet())).isEqualTo(29_649_00)
        assertThat(usable(setOf("7788"))).isEqualTo(9_649_00)
    }

    /**
     * Excluded in `affordability`, not merely on the screen.
     *
     * This is the assertion that would have caught the card bug. Both call sites hand
     * `affordability` the unpartitioned list, so a kind honoured only by the panel is a
     * kind the safe-to-spend figure ignores.
     */
    @Test
    fun `the exclusion lives in the money model and not the panel`() {
        val single = MonthlyAggregator.accountBalances(
            transactions = listOf(payment("7788", 20_000_00)),
            savings = setOf("7788"),
        )
        // Null, not zero, and for the same reason as a converted card: with the only
        // account put aside, nobody has quoted a spendable balance at all. Zero would be a
        // claim; null is the truth.
        assertThat(
            affordability(
                budgetPaise = 40_000_00,
                householdSpentPaise = 0,
                balances = single,
            ).usablePaise
        ).isNull()
    }

    /** A deposit with no figure is not an account of unknown balance, so it warns nobody. */
    @Test
    fun `an unknown deposit raises no unknown-account warning`() {
        val result = affordability(
            budgetPaise = null,
            householdSpentPaise = 0,
            balances = MonthlyAggregator.accountBalances(
                transactions = listOf(payment("7788", null)),
                savings = setOf("7788"),
            ),
        )
        assertThat(result.unknownAccounts).isEqualTo(0)
    }

    /**
     * A deposit no bank messages about still exists.
     *
     * This is the ordinary case rather than the exotic one — an FD is precisely the kind of
     * account that sends nothing for a year. The rule alone has to put it on screen, the
     * way a card key or a typed balance does, or declaring it would appear to do nothing.
     */
    @Test
    fun `a deposit with no transactions and no typed figure still appears`() {
        val rows = MonthlyAggregator.accountBalances(
            transactions = emptyList(),
            savings = setOf("FD with Federal"),
        )
        assertThat(rows.map { it.key }).containsExactly("FD with Federal")
        assertThat(rows.single().isSavings).isTrue()
        assertThat(rows.single().balancePaise).isNull()
    }

    /** A hand-typed deposit figure is carried, and still kept out of what is left. */
    @Test
    fun `a typed deposit figure is shown but not spendable`() {
        val rows = MonthlyAggregator.accountBalances(
            transactions = emptyList(),
            manual = mapOf(
                "FD" to ManualBalance(paise = 50_000_00, setAt = 1_000L, bank = "Federal Bank"),
            ),
            savings = setOf("FD"),
        )
        assertThat(rows.single().balancePaise).isEqualTo(50_000_00)
        assertThat(
            affordability(budgetPaise = null, householdSpentPaise = 0, balances = rows).usablePaise
        ).isNull()
    }

    /**
     * Three kinds, three totals, and no row in two of them at once.
     *
     * The panel partitions on `isCard` first and `isSavings` second, so an account that
     * were somehow both would land under "Owed on cards" and the household's answer would
     * be silently discarded. The chooser is one value for exactly this reason; this test
     * pins what the data model does if the two rules ever disagree anyway.
     */
    @Test
    fun `a card and a deposit are different rows and different totals`() {
        val rows = MonthlyAggregator.accountBalances(
            transactions = listOf(payment("3062", 9_649_00), payment("2020", 834_00)),
            cards = mapOf("2020" to CardInfo()),
            savings = setOf("7788"),
            manual = mapOf(
                "7788" to ManualBalance(paise = 20_000_00, setAt = 1_000L, bank = "Federal Bank"),
            ),
        )
        val (cards, notCards) = rows.partition { it.isCard }
        val (aside, accounts) = notCards.partition { it.isSavings }

        assertThat(cards.map { it.key }).containsExactly("2020")
        assertThat(aside.map { it.key }).containsExactly("7788")
        assertThat(accounts.map { it.key }).containsExactly("3062")

        // Only the middle one is money to spend. The other two are real and are reported;
        // they are simply not the same quantity.
        assertThat(
            affordability(budgetPaise = null, householdSpentPaise = 0, balances = rows).usablePaise
        ).isEqualTo(9_649_00)
    }

    /** Declaring it is not a one-way door — the money comes back when the deposit matures. */
    @Test
    fun `it can be turned back into an ordinary account`() {
        val back = balances(emptySet()).single { it.key == "7788" }
        assertThat(back.isSavings).isFalse()
        assertThat(
            affordability(
                budgetPaise = null,
                householdSpentPaise = 0,
                balances = balances(emptySet()),
            ).usablePaise
        ).isEqualTo(29_649_00)
    }
}
