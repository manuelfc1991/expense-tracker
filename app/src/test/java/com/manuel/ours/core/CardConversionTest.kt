package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.affordability
import com.manuel.ours.domain.model.CardInfo
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * Turning an account the app already knows about into a credit card.
 *
 * The "what kind" question was only ever asked when adding an account by hand — and the
 * accounts that matter here are not added by hand, they are discovered from bank
 * messages. So a credit card the parser found was a bank account permanently.
 *
 * That is a money bug, not a label. A card balance is what you **owe**; while it sits in
 * "What is left" the app reports a debt as spendable capacity, with the sign inverted.
 * The Accounts panel and `affordability` both partition on `isCard`, so flipping it moves
 * the figure between two totals that mean opposite things.
 */
class CardConversionTest {

    private fun payment(tail: String, balance: Long?) = Transaction(
        id = "t-$tail",
        amountPaise = 1_000_00,
        type = TxnType.DEBIT,
        merchant = "Shop",
        category = Category.SHOPPING,
        occurredAt = 2_000L,
        accountTail = tail,
        bank = "ICICI Bank",
        ownerUid = "manuel",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
        balancePaise = balance,
    )

    private fun balances(cards: Map<String, CardInfo>) = MonthlyAggregator.accountBalances(
        transactions = listOf(payment("3008", 4_200_00)),
        cards = cards,
    )

    @Test
    fun `an account the ledger discovered can become a card`() {
        assertThat(balances(emptyMap()).single().isCard).isFalse()
        assertThat(balances(mapOf("3008" to CardInfo())).single().isCard).isTrue()
    }

    /**
     * The figure that actually moves: ₹4,200 counted as money to spend before, and not at
     * all after.
     *
     * Afterwards the answer is **null**, not zero, and that distinction is the app's rule
     * rather than an accident — with the only account converted there is no longer any
     * account anybody has quoted a spendable balance for. Reporting ₹0 would say the
     * household has nothing, which is a claim; null says nobody has told us, which is the
     * truth. This test asserted 0 first and was wrong.
     */
    @Test
    fun `converting to a card takes its balance out of what you can spend`() {
        fun spendable(cards: Map<String, CardInfo>) = affordability(
            budgetPaise = 38_500_00,
            householdSpentPaise = 0,
            balances = balances(cards),
        ).usablePaise

        assertThat(spendable(emptyMap())).isEqualTo(4_200_00)
        assertThat(spendable(mapOf("3008" to CardInfo()))).isNull()
    }

    /** With a real account alongside it, the card simply drops out of the total. */
    @Test
    fun `a card's balance is left out while other accounts still count`() {
        val withBoth = MonthlyAggregator.accountBalances(
            transactions = listOf(payment("3008", 4_200_00), payment("3062", 9_649_00)),
            cards = mapOf("3008" to CardInfo()),
        )
        val result = affordability(
            budgetPaise = 38_500_00,
            householdSpentPaise = 0,
            balances = withBoth,
        )
        assertThat(result.usablePaise).isEqualTo(9_649_00)
    }

    /** A card with no balance is not an unknown account, so it raises no warning. */
    @Test
    fun `a converted card is not counted as an account of unknown balance`() {
        val result = affordability(
            budgetPaise = null,
            householdSpentPaise = 0,
            balances = MonthlyAggregator.accountBalances(
                transactions = listOf(payment("3008", null)),
                cards = mapOf("3008" to CardInfo()),
            ),
        )
        assertThat(result.unknownAccounts).isEqualTo(0)
    }

    /** Converting back is a real operation, not a one-way door. */
    @Test
    fun `converting back restores it to what is left`() {
        val back = balances(emptyMap()).single()
        assertThat(back.isCard).isFalse()
        assertThat(back.usablePaise).isEqualTo(4_200_00)
    }

    /**
     * The limit and the due day travel on the same rule, so writing one must not drop the
     * other — the mistake that would leave a card reminding on time with no room shown, or
     * the reverse.
     */
    @Test
    fun `limit and due day both survive on a converted card`() {
        val card = balances(mapOf("3008" to CardInfo(limitPaise = 50_000_00, dueDay = 18))).single()
        assertThat(card.limitPaise).isEqualTo(50_000_00)
        assertThat(card.dueDay).isEqualTo(18)
    }

    // ── cards the app already knows about ────────────────────────────────────

    /**
     * `BankRules` marks ten senders as cards and nothing read the flag, so a card the
     * parser had positively identified still arrived as a bank account. On this household
     * that is the Utkarsh SuperCard, whose purchases come from UTKSPR.
     */
    @Test
    fun `the senders marked as cards are recognised as cards`() {
        assertThat(com.manuel.ours.data.sms.BankRules.isCardBank("Utkarsh SuperCard")).isTrue()
        assertThat(com.manuel.ours.data.sms.BankRules.isCardBank("slice")).isTrue()
        assertThat(com.manuel.ours.data.sms.BankRules.isCardBank("HDFC Card")).isTrue()
    }

    /** And an ordinary bank is not one, which is what stops this over-reaching. */
    @Test
    fun `a bank account is not mistaken for a card`() {
        assertThat(com.manuel.ours.data.sms.BankRules.isCardBank("Kerala Gramin Bank")).isFalse()
        assertThat(com.manuel.ours.data.sms.BankRules.isCardBank("Federal Bank")).isFalse()
        assertThat(com.manuel.ours.data.sms.BankRules.isCardBank(null)).isFalse()
    }

    /**
     * "ICICI Bank" and "ICICI Card" are different rules and only one is a card. Matching
     * loosely on the bank name would have swept the current account in with it.
     */
    @Test
    fun `a bank and its card arm are told apart`() {
        assertThat(com.manuel.ours.data.sms.BankRules.isCardBank("ICICI Bank")).isFalse()
        assertThat(com.manuel.ours.data.sms.BankRules.isCardBank("ICICI Card")).isTrue()
    }
}
