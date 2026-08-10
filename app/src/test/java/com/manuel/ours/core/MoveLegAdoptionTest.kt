package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.db.TransactionEntity
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import org.junit.Test

/**
 * Whether stating a move relabels a row the bank already sent, or writes a new one.
 *
 * The costs are asymmetric and both are bad. Adopt too eagerly and a move silently relabels an
 * unrelated payment of the same size — the money is still counted, but as the wrong thing, and
 * nobody will ever notice. Adopt too shyly and the app writes a second row for money that left
 * the account once, so a ₹5,000 transfer removes ₹10,000 from a balance somebody is about to
 * spend against.
 *
 * The rule is pure so it can be tested without a database, for the same reason
 * `categoryForKind` was extracted in 7.5: the one rule a defence rests on should not be
 * reachable only through Room, a parser and a DAO. Rows here stand in for what
 * `findNearby` returns, which is already narrowed to the exact amount and a day either side.
 */
class MoveLegAdoptionTest {

    private val at = 1_800_000_000_000L

    private fun row(
        id: String,
        type: TxnType = TxnType.DEBIT,
        tail: String? = "3062",
        bank: String? = "Kerala Gramin Bank",
        dedupeAt: Long = at,
        transferPeerId: String? = null,
        category: Category = Category.TRANSFERS,
    ) = TransactionEntity(
        id = id,
        amountPaise = 5_000_00L,
        type = type.name,
        merchant = "Unknown payee",
        category = category.name,
        occurredAt = dedupeAt,
        accountTail = tail,
        refNo = null,
        bank = bank,
        note = null,
        splitType = SplitType.SHARED.name,
        source = TxnSource.SMS.name,
        ownerUid = "manuel",
        ownerName = "Manuel",
        needsReview = false,
        rawSms = null,
        deleted = false,
        transferPeerId = transferPeerId,
        dedupeKey = "k:$id",
        dedupeAt = dedupeAt,
        updatedAtLamport = 1L,
        updatedByDevice = "dev",
    )

    /** The out leg's rule: money must have left that account. */
    private fun outLeg(rows: List<TransactionEntity>, excludeId: String? = null) =
        TransactionRepository.adoptableLeg(rows, "3062", at, excludeId) {
            it.type == TxnType.DEBIT.name
        }

    // ── Adopting ──────────────────────────────────────────────────────────────────────

    /**
     * The ordinary case, and the one that decides whether the feature doubles money.
     *
     * The SMS lands in seconds; the person opens the app afterwards and says what the payment
     * was. There must be one row at the end of that, not two.
     */
    @Test
    fun `a debit the bank already reported is adopted`() {
        assertThat(outLeg(listOf(row("sms")))?.id).isEqualTo("sms")
    }

    /** Nothing to adopt is the other ordinary case — a move nobody's bank messaged about. */
    @Test
    fun `nothing is adopted when the bank sent nothing`() {
        assertThat(outLeg(emptyList())).isNull()
    }

    /**
     * Nearest in time, not first found.
     *
     * A household that pays two card bills can genuinely have two payments of one size on one
     * day, and pairing the wrong halves would leave both moves describing the wrong money.
     */
    @Test
    fun `the nearest row in time wins`() {
        val rows = listOf(
            row("far", dedupeAt = at - 20 * 60 * 60 * 1000L),
            row("near", dedupeAt = at + 60_000L),
        )
        assertThat(outLeg(rows)?.id).isEqualTo("near")
    }

    // ── Refusing ──────────────────────────────────────────────────────────────────────

    /** A row already belongs to one move or to none. Stealing it would orphan the first. */
    @Test
    fun `a row already half of another move is left alone`() {
        assertThat(outLeg(listOf(row("taken", transferPeerId = "other")))).isNull()
    }

    /**
     * A different account is a different payment, whatever it cost.
     *
     * The amount and the day are already fixed by the time these rows are gathered, so the
     * account is the only thing left separating "my transfer" from "somebody's ₹5,000 rent".
     */
    @Test
    fun `a row on another account is not adopted`() {
        val rows = listOf(row("elsewhere", tail = "4657", bank = "Federal Bank"))
        assertThat(outLeg(rows)).isNull()
    }

    /** Money arriving is not money leaving, so the out leg refuses a credit. */
    @Test
    fun `a credit is not adopted as the leg that left`() {
        assertThat(outLeg(listOf(row("credit", type = TxnType.CREDIT)))).isNull()
    }

    /** The row chosen for the other end cannot serve as this one as well. */
    @Test
    fun `the leg already taken by the other end is excluded`() {
        assertThat(outLeg(listOf(row("both")), excludeId = "both")).isNull()
    }

    // ── The card end, where direction is not TxnType ──────────────────────────────────

    /**
     * A card issuer's acknowledgement arrives as a *debit* — `DEBIT_VERB` holds "payment of"
     * and is tested first — so the arriving end cannot ask for a credit when the destination is
     * a card. Asking would refuse the one message the card ever sends about being paid, and the
     * app would write a second row beside it.
     */
    @Test
    fun `a card acknowledgement is adopted as the arriving leg despite being a debit`() {
        val ack = row(
            "ack", type = TxnType.DEBIT, tail = "3008", bank = "ICICI Bank",
            // Date-only, so it lands at midnight, most of a day from the payment.
            dedupeAt = at - 19 * 60 * 60 * 1000L,
            category = Category.CARD_PAYMENT,
        )
        val destinationIsCard = true
        val found = TransactionRepository.adoptableLeg(listOf(ack), "3008", at, null) {
            destinationIsCard || it.type == TxnType.CREDIT.name
        }
        assertThat(found?.id).isEqualTo("ack")
    }

    /**
     * And that licence is confined to cards. On an ordinary account a debit of the right size is
     * an unrelated payment going out, and relabelling it as money arriving would be the eager
     * failure — invisible, and wrong in both accounts at once.
     */
    @Test
    fun `a debit is not adopted as the arriving leg on an ordinary account`() {
        val destinationIsCard = false
        val found = TransactionRepository.adoptableLeg(listOf(row("debit")), "3062", at, null) {
            destinationIsCard || it.type == TxnType.CREDIT.name
        }
        assertThat(found).isNull()
    }
}
