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
 * Which way a card's outstanding moves, per kind of message.
 *
 * `accountBalances` adjusts a **typed** figure by the movements seen since — a chequebook,
 * not a reconstruction. On a bank account the sign comes from [TxnType] and the category is
 * irrelevant. On a card neither of those holds, because the figure is a debt and *both*
 * messages that move it arrive as debits:
 *
 * - `"your SuperCard 2020 debited for INR 72.00"` — a purchase, so the debt grows
 * - `"Payment of Rs 468.41 has been received on your ICICI Bank Credit Card XX3008"` — a
 *   settlement, so the debt shrinks; it reads as a debit only because `DEBIT_VERB` holds
 *   "payment of" and is tested before `CREDIT_VERB`
 *
 * So a card can take neither the bank-account rule nor its mirror image: one sign is right
 * for purchases and the other for settlements. Flipping the lot was the first attempt and
 * would have pushed the ICICI payment the wrong way in order to fix the SuperCard — which
 * is why both cases are pinned here together, and why neither may be changed alone.
 *
 * Same family as [PutAsideTest] and [CardConversionTest]: the kind honoured where the money
 * is presented and ignored where it is computed.
 */
class CardDriftTest {

    private val typedAt = 1_000L

    private fun row(
        id: String,
        type: TxnType,
        paise: Long,
        category: Category,
    ) = Transaction(
        id = id,
        amountPaise = paise,
        type = type,
        merchant = "Card",
        category = category,
        occurredAt = typedAt + 1,
        accountTail = "3008",
        bank = "ICICI Bank",
        ownerUid = "manuel",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
        balancePaise = null,
    )

    /** Outstanding on a card typed at ₹10,531.59, after the given movements. */
    private fun owedAfter(vararg rows: Transaction): Long? =
        MonthlyAggregator.accountBalances(
            transactions = rows.toList(),
            manual = mapOf("3008" to ManualBalance(10_531_59L, typedAt, "ICICI Bank", "manuel")),
            cards = mapOf("3008" to CardInfo(11_000_00L, 30)),
        ).single { it.isCard }.balancePaise

    /**
     * The live case. Paid through CRED: ₹468.41 reached the card, of which ₹43 was points,
     * so only ₹425.41 left the bank. The card leg is the one that settles the card.
     */
    @Test
    fun `a bill payment reduces what is owed`() {
        val owed = owedAfter(row("pay", TxnType.DEBIT, 468_41L, Category.SELF_TRANSFER))
        assertThat(owed).isEqualTo(10_063_18L)
    }

    /** An unregistered card's bill is filed `CARD_PAYMENT` and settles just the same. */
    @Test
    fun `a card payment on an unregistered card reduces what is owed`() {
        val owed = owedAfter(row("pay", TxnType.DEBIT, 468_41L, Category.CARD_PAYMENT))
        assertThat(owed).isEqualTo(10_063_18L)
    }

    /** A purchase is the other direction, and arrives as a debit exactly like the bill. */
    @Test
    fun `a purchase increases what is owed`() {
        val owed = owedAfter(row("buy", TxnType.DEBIT, 797_00L, Category.FOOD))
        assertThat(owed).isEqualTo(11_328_59L)
    }

    /** Money credited back to the card — a refund — reduces the debt. */
    @Test
    fun `a refund credited to the card reduces what is owed`() {
        val owed = owedAfter(row("ref", TxnType.CREDIT, 500_00L, Category.INCOME))
        assertThat(owed).isEqualTo(10_031_59L)
    }

    /** The two together, which is what a real month on a card looks like. */
    @Test
    fun `purchases and a settlement net out`() {
        val owed = owedAfter(
            row("buy", TxnType.DEBIT, 797_00L, Category.FOOD),
            row("pay", TxnType.DEBIT, 468_41L, Category.SELF_TRANSFER),
        )
        assertThat(owed).isEqualTo(10_860_18L)
    }
}
