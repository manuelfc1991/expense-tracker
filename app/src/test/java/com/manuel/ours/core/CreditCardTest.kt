package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.CardInfo
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.ManualBalance
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * A credit card balance is money **owed**, and must never join the money you have.
 *
 * The Accounts panel totals "what is left" and `Affordability` spends against that figure.
 * Folding ₹4,200 of card debt into it would tell the household it has ₹4,200 more to spend
 * than it does — the one direction of error that actually costs money.
 */
class CreditCardTest {

    private fun debit(rupees: Long, tail: String?, bank: String?) = Transaction(
        id = "t$rupees$tail",
        amountPaise = rupees * 100,
        type = TxnType.DEBIT,
        merchant = "Shop",
        category = Category.FOOD,
        occurredAt = 1_000L,
        accountTail = tail,
        bank = bank,
        ownerUid = "me",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
    )

    private fun balances(cards: Map<String, CardInfo>) = MonthlyAggregator.accountBalances(
        transactions = listOf(
            debit(500, "3062", "Kerala Gramin Bank"),
            debit(640, "8842", "Utkarsh SuperCard"),
        ),
        manual = mapOf(
            "3062" to ManualBalance(paise = 9_649_00, setAt = 2_000L, bank = "Kerala Gramin Bank"),
            "8842" to ManualBalance(paise = 4_200_00, setAt = 2_000L, bank = "Utkarsh SuperCard"),
        ),
        cards = cards,
    )

    @Test
    fun `an account declared a card is marked as one, and the others are not`() {
        val out = balances(mapOf("8842" to CardInfo(limitPaise = 20_000_00)))
        assertThat(out.single { it.key == "8842" }.isCard).isTrue()
        assertThat(out.single { it.key == "3062" }.isCard).isFalse()
    }

    /**
     * The split the Accounts panel makes. Two totals, and the card is in neither the same
     * one as the bank account nor added to it.
     */
    @Test
    fun `what is left excludes the card, and owed is its own figure`() {
        val out = balances(mapOf("8842" to CardInfo()))
        val (cards, accounts) = out.partition { it.isCard }

        assertThat(accounts.mapNotNull { it.usablePaise }.sum()).isEqualTo(9_649_00)
        assertThat(cards.mapNotNull { it.balancePaise }.sum()).isEqualTo(4_200_00)
        // The number that would have been reported if they were summed, which is the bug.
        assertThat(accounts.mapNotNull { it.usablePaise }.sum() + 4_200_00)
            .isNotEqualTo(9_649_00)
    }

    @Test
    fun `the limit says how much room is left, and only when both halves are known`() {
        val withLimit = balances(mapOf("8842" to CardInfo(limitPaise = 20_000_00)))
            .single { it.key == "8842" }
        assertThat(withLimit.limitPaise).isEqualTo(20_000_00)
        assertThat(withLimit.limitPaise!! - withLimit.balancePaise!!).isEqualTo(15_800_00)

        // No limit given: the card still works, it just cannot say.
        val without = balances(mapOf("8842" to CardInfo())).single { it.key == "8842" }
        assertThat(without.isCard).isTrue()
        assertThat(without.limitPaise).isNull()
    }

    /**
     * A card the household has not declared behaves exactly as before.
     *
     * This is what keeps the change from reaching the ICICI card, whose purchases never
     * arrive by SMS and whose bill is therefore still the only record of that money.
     */
    @Test
    fun `nothing changes for an account nobody called a card`() {
        val out = balances(emptyMap())
        assertThat(out.none { it.isCard }).isTrue()
        assertThat(out.mapNotNull { it.usablePaise }.sum()).isEqualTo(9_649_00 + 4_200_00)
    }
}
