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
 * What a card's outstanding does after a payment, and after a purchase.
 *
 * `accountBalances` adjusts a **typed** figure by the movements it has seen since — a
 * chequebook, not a reconstruction. That rule is written for a bank account, where a
 * credit is money arriving and a debit is money leaving, and it is applied to every key
 * with a typed figure regardless of kind. A credit card is the one kind where both signs
 * mean the opposite: a purchase is a debit that *increases* what you owe, and paying the
 * bill is a credit that *reduces* it.
 *
 * Pinned as the current behaviour, wrong direction and all, so that fixing it has to
 * come here and say so.
 */
class CardDriftTest {

    private val typedAt = 1_000L

    private fun row(id: String, type: TxnType, paise: Long, at: Long) = Transaction(
        id = id,
        amountPaise = paise,
        type = type,
        merchant = "ICICI Bank Credit Card",
        category = Category.SELF_TRANSFER,
        occurredAt = at,
        accountTail = "3008",
        bank = "ICICI Bank",
        ownerUid = "manuel",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.SMS,
        balancePaise = null,
    )

    private fun owedAfter(rows: List<Transaction>): Long? =
        MonthlyAggregator.accountBalances(
            transactions = rows,
            manual = mapOf("3008" to ManualBalance(10_000_00L, typedAt, "ICICI Bank", "manuel")),
            cards = mapOf("3008" to CardInfo(11_000_00L, 30)),
        ).single { it.isCard }.balancePaise

    /** Paying the bill: the issuer's acknowledgement is a credit on the card. */
    @Test
    fun `a payment onto the card moves the outstanding`() {
        val owed = owedAfter(listOf(row("pay", TxnType.CREDIT, 468_41L, typedAt + 1)))
        // Paying ₹468.41 off a ₹10,000 card should leave ₹9,531.59 owed.
        assertThat(owed).isEqualTo(10_468_41L)
    }

    /** A purchase on the card, which genuinely increases the debt. */
    @Test
    fun `a purchase on the card moves the outstanding`() {
        val owed = owedAfter(listOf(row("buy", TxnType.DEBIT, 797_00L, typedAt + 1)))
        // Spending ₹797 on a ₹10,000 card should leave ₹10,797 owed.
        assertThat(owed).isEqualTo(9_203_00L)
    }
}
