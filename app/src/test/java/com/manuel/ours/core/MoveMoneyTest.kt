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
 * What the two rows of a stated move do to the month and to the accounts.
 *
 * `moveMoney` writes a pair — money out of one account, money into another, both
 * [Category.SELF_TRANSFER] and linked by `transferPeerId`. This is about the pair's effect
 * rather than about the writing of it, because the effect is the part that can be wrong
 * silently: a leg filed the wrong way costs the household nothing visible until a total is
 * read, and then it is off by the size of the move in a figure somebody is about to spend
 * against.
 *
 * The case that forced the feature is the last one here. The partner's SBI has no sender on
 * this phone, so money sent there produces exactly one message — the debit — and every
 * mechanism the app had for recognising a transfer works by pairing *two*. Without a stated
 * move, ₹10,000 to a household account is ₹10,000 of spending that never happened.
 *
 * Same family as [PutAsideTest], [CardConversionTest] and [CardDriftTest]: a kind honoured in
 * one place and ignored in another.
 */
class MoveMoneyTest {

    private val typedAt = 1_000L
    private val movedAt = typedAt + 1

    /** As `moveMoney` writes it: the money leaving. */
    private fun outLeg(paise: Long, tail: String, bank: String) = leg(
        id = "out", paise = paise, type = TxnType.DEBIT, tail = tail, bank = bank,
    )

    /** As `moveMoney` writes it: the money arriving. */
    private fun inLeg(paise: Long, tail: String, bank: String) = leg(
        id = "in", paise = paise, type = TxnType.CREDIT, tail = tail, bank = bank,
    )

    private fun leg(
        id: String,
        paise: Long,
        type: TxnType,
        tail: String,
        bank: String,
    ) = Transaction(
        id = id,
        amountPaise = paise,
        type = type,
        // Both legs read the same line, which is what lets the statement print
        // "from → to" without knowing which half it is looking at.
        merchant = "Kerala Gramin ···3062 → ICICI ···3008",
        category = Category.SELF_TRANSFER,
        occurredAt = movedAt,
        accountTail = tail,
        bank = bank,
        ownerUid = "manuel",
        ownerName = "Manuel",
        splitType = SplitType.SHARED,
        source = TxnSource.MANUAL,
        transferPeerId = if (id == "out") "in" else "out",
        balancePaise = null,
    )

    private fun balances(
        rows: List<Transaction>,
        manual: Map<String, ManualBalance>,
        cards: Map<String, CardInfo> = emptyMap(),
    ) = MonthlyAggregator.accountBalances(
        transactions = rows,
        manual = manual,
        cards = cards,
    ).associateBy { it.key }

    // ── Neither leg is spending, and neither is income ────────────────────────────────

    /**
     * The whole point. Money that goes from one place the household owns to another has not
     * been consumed, and counting the debit would charge the budget for it.
     */
    @Test
    fun `a move is not spending`() {
        val rows = listOf(
            outLeg(5_000_00L, "3062", "Kerala Gramin Bank"),
            inLeg(5_000_00L, "3008", "ICICI Bank"),
        )
        assertThat(MonthlyAggregator.totalSpent(rows)).isEqualTo(0L)
    }

    /**
     * And the arriving half is not earnings. Filing it as income would be the mirror mistake:
     * the month would report ₹5,000 received that nobody paid the household.
     */
    @Test
    fun `the arriving leg is not income`() {
        val rows = listOf(
            outLeg(5_000_00L, "3062", "Kerala Gramin Bank"),
            inLeg(5_000_00L, "3008", "ICICI Bank"),
        )
        assertThat(MonthlyAggregator.totalReceived(rows)).isEqualTo(0L)
    }

    /**
     * It did still leave the account, though, and *that* total is supposed to notice.
     *
     * `totalDebited` is the "everything that left our accounts" figure, savings and
     * self-transfers included — the one place a move is meant to show up.
     */
    @Test
    fun `the money is still recorded as having left the account`() {
        val rows = listOf(
            outLeg(5_000_00L, "3062", "Kerala Gramin Bank"),
            inLeg(5_000_00L, "3008", "ICICI Bank"),
        )
        assertThat(MonthlyAggregator.totalDebited(rows)).isEqualTo(5_000_00L)
    }

    // ── Bank → credit card ────────────────────────────────────────────────────────────

    /** Rule 1: the bank goes down by what left it, the card's outstanding down by what reached it. */
    @Test
    fun `paying a card moves both figures the right way`() {
        val accounts = balances(
            rows = listOf(
                outLeg(5_000_00L, "3062", "Kerala Gramin Bank"),
                inLeg(5_000_00L, "3008", "ICICI Bank"),
            ),
            manual = mapOf(
                "3062" to ManualBalance(20_000_00L, typedAt, "Kerala Gramin Bank", "manuel"),
                "3008" to ManualBalance(10_531_59L, typedAt, "ICICI Bank", "manuel"),
            ),
            cards = mapOf("3008" to CardInfo(11_000_00L, 30)),
        )
        assertThat(accounts.getValue("3062").balancePaise).isEqualTo(15_000_00L)
        assertThat(accounts.getValue("3008").balancePaise).isEqualTo(5_531_59L)
    }

    /**
     * The CRED case, with the household's own figures.
     *
     * ₹468.41 settled the card; only ₹425.41 left Kerala Gramin, because ₹43 of it was points.
     * Both numbers are true and each belongs against the account it actually moved on — so the
     * bank falls by the smaller and the card by the larger, and the ₹43 gap is named nowhere.
     * Naming it would create an entry that every total then has to exclude.
     */
    @Test
    fun `points pay part of a card bill and neither figure is fudged`() {
        val accounts = balances(
            rows = listOf(
                outLeg(425_41L, "3062", "Kerala Gramin Bank"),
                inLeg(468_41L, "3008", "ICICI Bank"),
            ),
            manual = mapOf(
                "3062" to ManualBalance(20_000_00L, typedAt, "Kerala Gramin Bank", "manuel"),
                "3008" to ManualBalance(10_531_59L, typedAt, "ICICI Bank", "manuel"),
            ),
            cards = mapOf("3008" to CardInfo(11_000_00L, 30)),
        )
        assertThat(accounts.getValue("3062").balancePaise).isEqualTo(19_574_59L)
        assertThat(accounts.getValue("3008").balancePaise).isEqualTo(10_063_18L)
    }

    /**
     * An *adopted* arriving leg keeps the debit the issuer's message parsed as.
     *
     * "Payment of Rs 468.41 has been received on your ICICI Bank Credit Card XX3008" reads as a
     * debit because `DEBIT_VERB` holds "payment of" and is tested before `CREDIT_VERB`. The row
     * is not rewritten when a move adopts it — the bank's own figures are the ones worth
     * keeping — so the card has to settle on the *category*, exactly as `CardDriftTest` pins.
     * If this ever comes back 10,999.99 something has started deciding a card by its type again.
     */
    @Test
    fun `an adopted card leg settles even though it parsed as a debit`() {
        val adopted = leg("in", 468_41L, TxnType.DEBIT, "3008", "ICICI Bank")
        val accounts = balances(
            rows = listOf(outLeg(425_41L, "3062", "Kerala Gramin Bank"), adopted),
            manual = mapOf(
                "3008" to ManualBalance(10_531_59L, typedAt, "ICICI Bank", "manuel"),
            ),
            cards = mapOf("3008" to CardInfo(11_000_00L, 30)),
        )
        assertThat(accounts.getValue("3008").balancePaise).isEqualTo(10_063_18L)
    }

    // ── Bank → bank, and bank → the partner's account ─────────────────────────────────

    /** Rule 2: out of one, into the other, and the household is no poorer. */
    @Test
    fun `moving between two own accounts leaves the total untouched`() {
        val manual = mapOf(
            "3062" to ManualBalance(20_000_00L, typedAt, "Kerala Gramin Bank", "manuel"),
            "4657" to ManualBalance(5_000_00L, typedAt, "Federal Bank", "manuel"),
        )
        val rows = listOf(
            leg("out", 10_000_00L, TxnType.DEBIT, "3062", "Kerala Gramin Bank"),
            leg("in", 10_000_00L, TxnType.CREDIT, "4657", "Federal Bank"),
        )
        val accounts = balances(rows, manual)
        assertThat(accounts.getValue("3062").balancePaise).isEqualTo(10_000_00L)
        assertThat(accounts.getValue("4657").balancePaise).isEqualTo(15_000_00L)
        // The sum is what did not change, which is the claim worth making.
        assertThat(accounts.values.sumOf { it.balancePaise ?: 0L }).isEqualTo(25_000_00L)
    }

    /**
     * Rule 3, and the reason the feature exists.
     *
     * The partner's SBI sends nothing to this phone, so the arriving leg has no message and
     * never will. Without a stated move the debit stands alone: `markSelfTransfers` has nothing
     * to pair it with, so it stays spending, and her balance — a figure somebody typed once —
     * sits unchanged while the money is in fact already there.
     */
    @Test
    fun `money sent to the partner reaches her account instead of counting as spending`() {
        val manual = mapOf(
            "3062" to ManualBalance(20_000_00L, typedAt, "Kerala Gramin Bank", "manuel"),
            "SBI" to ManualBalance(2_000_00L, typedAt, "SBI", "beula"),
        )
        val rows = listOf(
            leg("out", 10_000_00L, TxnType.DEBIT, "3062", "Kerala Gramin Bank"),
            // No tail: SBI is keyed by its name, because no message here ever names an account.
            Transaction(
                id = "in",
                amountPaise = 10_000_00L,
                type = TxnType.CREDIT,
                merchant = "Kerala Gramin ···3062 → SBI",
                category = Category.SELF_TRANSFER,
                occurredAt = movedAt,
                accountTail = null,
                bank = "SBI",
                ownerUid = "manuel",
                ownerName = "Manuel",
                splitType = SplitType.SHARED,
                source = TxnSource.MANUAL,
                transferPeerId = "out",
            ),
        )
        assertThat(MonthlyAggregator.totalSpent(rows)).isEqualTo(0L)
        val accounts = balances(rows, manual)
        assertThat(accounts.getValue("3062").balancePaise).isEqualTo(10_000_00L)
        assertThat(accounts.getValue("SBI").balancePaise).isEqualTo(12_000_00L)
    }

    /**
     * The defect, stated: one leg alone is spending.
     *
     * Kept as a test rather than as a sentence in a comment, because it is what the feature is
     * measured against — and because it is what the ledger still does for every transfer nobody
     * states.
     */
    @Test
    fun `a lone debit to the partner is counted as spending`() {
        val alone = Transaction(
            id = "out",
            amountPaise = 10_000_00L,
            type = TxnType.DEBIT,
            merchant = "Unknown payee",
            category = Category.TRANSFERS,
            occurredAt = movedAt,
            accountTail = "3062",
            bank = "Kerala Gramin Bank",
            ownerUid = "manuel",
            ownerName = "Manuel",
            splitType = SplitType.SHARED,
            source = TxnSource.SMS,
        )
        assertThat(MonthlyAggregator.totalSpent(listOf(alone))).isEqualTo(10_000_00L)
    }

    // ── Cash ──────────────────────────────────────────────────────────────────────────

    /**
     * An ATM withdrawal is a move, not a purchase.
     *
     * Cash is an account in this app precisely so it can be one end of something. Counting the
     * withdrawal as spending charges the budget when the money is taken out and charges it
     * again for whatever it is then spent on.
     */
    @Test
    fun `taking cash out is a move rather than a purchase`() {
        val rows = listOf(
            leg("out", 2_000_00L, TxnType.DEBIT, "3062", "Kerala Gramin Bank"),
            Transaction(
                id = "in",
                amountPaise = 2_000_00L,
                type = TxnType.CREDIT,
                merchant = "Kerala Gramin ···3062 → Cash",
                category = Category.SELF_TRANSFER,
                occurredAt = movedAt,
                accountTail = null,
                bank = "Cash",
                ownerUid = "manuel",
                ownerName = "Manuel",
                splitType = SplitType.SHARED,
                source = TxnSource.MANUAL,
                transferPeerId = "out",
            ),
        )
        assertThat(MonthlyAggregator.totalSpent(rows)).isEqualTo(0L)
        assertThat(MonthlyAggregator.accountBalances(rows).map { it.key })
            .containsExactly("3062", "Cash")
    }
}
