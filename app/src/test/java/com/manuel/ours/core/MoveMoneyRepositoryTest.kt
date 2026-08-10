package com.manuel.ours.core

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.db.AppDatabase
import com.manuel.ours.data.db.TransactionEntity
import com.manuel.ours.data.db.toDomain
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.data.sync.LamportClock
import com.manuel.ours.domain.MonthlyAggregator
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.MoveSide
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `moveMoney` end to end, against a real database.
 *
 * [MoveMoneyTest] pins what the two rows *do* to the totals, and [MoveLegAdoptionTest] pins the
 * matching rule in isolation. Neither touches the thing between them: which rows actually get
 * written, whether an existing one is relabelled or a second one appears beside it, and whether
 * the two halves end up pointing at each other. That orchestration is where a feature like this
 * fails, and it fails by *adding money* — the worst direction, on a ledger that is the only copy
 * of a household's history.
 *
 * So this builds the repository for real: an in-memory Room database, the actual `AppPrefs`, the
 * actual clock. What it asserts is mostly row counts and links, because "there are two rows, not
 * three" is the whole safety property.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application. The real one boots Hilt, which opens SQLCipher, which needs the Android
// Keystore — absent on the JVM. Same reason `RescanIdempotencyTest` does this.
@Config(sdk = [33], application = android.app.Application::class)
class MoveMoneyRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TransactionRepository
    private lateinit var prefs: AppPrefs

    private val at = 1_800_000_000_000L
    private val kgb = MoveSide("3062", "Kerala Gramin Bank", "Kerala Gramin ···3062")
    private val icici = MoveSide("3008", "ICICI Bank", "ICICI ···3008")
    private val federal = MoveSide("4657", "Federal Bank", "Federal ···4657")
    private val sbi = MoveSide(null, "SBI", "SBI")
    private val cash = MoveSide(null, "Cash", "Cash")

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        prefs = AppPrefs(context)
        prefs.setSelf("manuel", "Manuel", "manuel@example.com")
        prefs.setHouseholdOwner(true)
        // Everything here happens well after the cutoff; left at its default a fresh
        // AppPrefs would be fine, but stating it keeps the tests independent of that default.
        prefs.setTrackingStartAt(at - 365L * 24 * 3_600_000L)
        repo = TransactionRepository(
            txnDao = db.transactionDao(),
            eventDao = db.syncEventDao(),
            merchantRuleDao = db.merchantRuleDao(),
            sharedRuleDao = db.sharedRuleDao(),
            parser = SmsParser(),
            prefs = prefs,
            clock = LamportClock(),
        )
        // The ICICI card, registered as the household has it registered.
        repo.setCard("3008", 11_000_00L, 30)
    }

    @After
    fun tearDown() = db.close()

    private fun live(): List<TransactionEntity> = runBlocking { db.transactionDao().allLive() }

    private fun leg(tail: String?, bank: String?): TransactionEntity? =
        live().firstOrNull { (it.accountTail?.takeIf(String::isNotBlank) ?: it.bank) == (tail ?: bank) }

    /** A row as an SMS import would have left it, so a move has something to adopt. */
    private fun seed(
        id: String,
        paise: Long,
        type: TxnType,
        tail: String?,
        bank: String?,
        occurredAt: Long = at,
        category: Category = Category.TRANSFERS,
        merchant: String = "Unknown payee",
        raw: String? = "the original message",
    ) = runBlocking {
        db.transactionDao().upsert(
            TransactionEntity(
                id = id,
                amountPaise = paise,
                type = type.name,
                merchant = merchant,
                category = category.name,
                occurredAt = occurredAt,
                accountTail = tail,
                refNo = null,
                bank = bank,
                note = null,
                splitType = SplitType.SHARED.name,
                source = TxnSource.SMS.name,
                ownerUid = "manuel",
                ownerName = "Manuel",
                needsReview = false,
                rawSms = raw,
                deleted = false,
                dedupeKey = "seed:$id",
                dedupeAt = occurredAt,
                updatedAtLamport = 1L,
                updatedByDevice = "seed",
            )
        )
    }

    private fun move(
        from: MoveSide = kgb,
        to: MoveSide = icici,
        out: Long = 5_000_00L,
        into: Long = out,
        occurredAt: Long = at,
        note: String? = null,
    ): Boolean = runBlocking { repo.moveMoney(from, to, out, into, occurredAt, note) }

    // ── Nothing recorded yet: both legs are written ───────────────────────────────────

    /**
     * The case the feature exists for. No bank message will ever arrive for the arriving side,
     * so if the app does not write it, nothing does.
     */
    @Test
    fun `a move nobody messaged about writes exactly two linked rows`() {
        assertThat(move(kgb, sbi, 10_000_00L)).isTrue()

        val rows = live()
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.category }.toSet())
            .containsExactly(Category.SELF_TRANSFER.name)

        val out = leg("3062", null)!!
        val into = leg(null, "SBI")!!
        assertThat(out.type).isEqualTo(TxnType.DEBIT.name)
        assertThat(into.type).isEqualTo(TxnType.CREDIT.name)
        assertThat(out.amountPaise).isEqualTo(10_000_00L)
        assertThat(into.amountPaise).isEqualTo(10_000_00L)
        // Each names the other, so neither can be read as anything else on its own.
        assertThat(out.transferPeerId).isEqualTo(into.id)
        assertThat(into.transferPeerId).isEqualTo(out.id)
    }

    /** Both rows read the same line, which is what lets the statement print it without direction logic. */
    @Test
    fun `both legs carry the from and to line`() {
        move(kgb, icici, 5_000_00L)
        assertThat(live().map { it.merchant }.toSet())
            .containsExactly("Kerala Gramin ···3062 → ICICI ···3008")
    }

    /** And the pair is worth nothing to the spending total, which is the point of all of it. */
    @Test
    fun `a move adds nothing to spending`() {
        move(kgb, sbi, 10_000_00L)
        val rows = live().map { it.toDomain() }
        assertThat(MonthlyAggregator.totalSpent(rows)).isEqualTo(0L)
        assertThat(MonthlyAggregator.totalReceived(rows)).isEqualTo(0L)
    }

    // ── Adoption: the bank got there first ────────────────────────────────────────────

    /**
     * The ordinary flow, and the one that decides whether this feature doubles money: the SMS
     * lands in seconds, the person opens the app afterwards and says what it was.
     *
     * Two rows at the end, not three. If this ever reads 3, a ₹5,000 transfer is taking ₹10,000
     * off the balance somebody is about to spend against.
     */
    @Test
    fun `a debit the bank already reported is relabelled rather than duplicated`() {
        seed("sms", 5_000_00L, TxnType.DEBIT, "3062", "Kerala Gramin Bank")

        move(kgb, icici, 5_000_00L)

        val rows = live()
        assertThat(rows).hasSize(2)
        val adopted = rows.first { it.id == "sms" }
        assertThat(adopted.category).isEqualTo(Category.SELF_TRANSFER.name)
        assertThat(adopted.transferPeerId).isNotNull()
        assertThat(rows.first { it.id != "sms" }.transferPeerId).isEqualTo("sms")
    }

    /**
     * An adopted row keeps everything the bank said. Only what the household has now stated
     * about it changes — the category, the line, the link.
     *
     * `rawSms` especially: it is the only record of the original message and the only way a
     * future parser fix can be applied to it.
     */
    @Test
    fun `an adopted row keeps the bank's own amount and message`() {
        seed("sms", 5_000_00L, TxnType.DEBIT, "3062", "Kerala Gramin Bank", raw = "Debited Rs 5000")

        move(kgb, icici, 5_000_00L)

        val adopted = live().first { it.id == "sms" }
        assertThat(adopted.amountPaise).isEqualTo(5_000_00L)
        assertThat(adopted.type).isEqualTo(TxnType.DEBIT.name)
        assertThat(adopted.rawSms).isEqualTo("Debited Rs 5000")
        assertThat(adopted.source).isEqualTo(TxnSource.SMS.name)
    }

    /**
     * Both halves already recorded — the bank's debit and the card issuer's acknowledgement.
     * Nothing new should be written at all; the move is pure relabelling.
     */
    @Test
    fun `when both messages already arrived the move writes no new rows`() {
        seed("bank", 5_000_00L, TxnType.DEBIT, "3062", "Kerala Gramin Bank")
        // Date-only, so it landed at midnight — most of a day from the payment it echoes.
        seed(
            "ack", 5_000_00L, TxnType.DEBIT, "3008", "ICICI Bank",
            occurredAt = at - 19 * 60 * 60 * 1000L, category = Category.CARD_PAYMENT,
        )

        move(kgb, icici, 5_000_00L)

        val rows = live()
        assertThat(rows.map { it.id }).containsExactly("bank", "ack")
        assertThat(rows.first { it.id == "bank" }.transferPeerId).isEqualTo("ack")
        assertThat(rows.first { it.id == "ack" }.transferPeerId).isEqualTo("bank")
    }

    /**
     * The card's acknowledgement keeps its debit, and must.
     *
     * "Payment of Rs …" parses as a debit because `DEBIT_VERB` holds "payment of". Rewriting it
     * to a credit to make the arrow look right would be inventing a fact; `accountBalances`
     * settles a card on category instead, which is what `CardDriftTest` pins.
     */
    @Test
    fun `an adopted card acknowledgement stays a debit`() {
        seed(
            "ack", 468_41L, TxnType.DEBIT, "3008", "ICICI Bank",
            occurredAt = at - 19 * 60 * 60 * 1000L, category = Category.CARD_PAYMENT,
        )

        move(kgb, icici, out = 425_41L, into = 468_41L)

        val ack = live().first { it.id == "ack" }
        assertThat(ack.type).isEqualTo(TxnType.DEBIT.name)
        assertThat(ack.category).isEqualTo(Category.SELF_TRANSFER.name)
        assertThat(ack.amountPaise).isEqualTo(468_41L)
    }

    /**
     * With equal amounts on both ends, the adopted row serves one end and a fresh row is written
     * for the other — the pair never collapses onto a single row pointing at itself.
     *
     * What actually prevents that is the *key* check, not the explicit exclusion of the row
     * already taken: the two ends are refused unless their keys differ, and a row has one key.
     * Mutation testing established this — removing the exclusion breaks nothing here — and the
     * name of this test used to claim otherwise, which would have read as coverage of a guard
     * nothing exercises.
     */
    @Test
    fun `equal amounts on both ends still produce two distinct rows`() {
        seed("only", 5_000_00L, TxnType.DEBIT, "3062", "Kerala Gramin Bank")

        move(kgb, federal, 5_000_00L)

        val rows = live()
        assertThat(rows).hasSize(2)
        val out = rows.first { it.id == "only" }
        assertThat(out.transferPeerId).isNotEqualTo("only")
        assertThat(rows.first { it.id != "only" }.accountTail).isEqualTo("4657")
    }

    /** A row already half of another move belongs to that one; a second move writes fresh rows. */
    @Test
    fun `a row already part of a move is not stolen by a second one`() {
        seed("sms", 5_000_00L, TxnType.DEBIT, "3062", "Kerala Gramin Bank")
        move(kgb, icici, 5_000_00L)
        val firstPeer = live().first { it.id == "sms" }.transferPeerId

        move(kgb, federal, 5_000_00L)

        val rows = live()
        // The first pair intact, plus a whole new pair.
        assertThat(rows).hasSize(4)
        assertThat(rows.first { it.id == "sms" }.transferPeerId).isEqualTo(firstPeer)
    }

    /**
     * Money arriving is not money leaving. On an ordinary account a debit of the right size is
     * somebody else's payment going out, and relabelling it would be wrong in both accounts at
     * once — the eager failure, which nobody would ever notice.
     */
    @Test
    fun `a debit on the destination account is not adopted as the arriving leg`() {
        seed("rent", 5_000_00L, TxnType.DEBIT, "4657", "Federal Bank", category = Category.RENT)

        move(kgb, federal, 5_000_00L)

        val rows = live()
        assertThat(rows).hasSize(3)
        // Untouched: still a debit, still rent, still not part of anything.
        val rent = rows.first { it.id == "rent" }
        assertThat(rent.category).isEqualTo(Category.RENT.name)
        assertThat(rent.transferPeerId).isNull()
    }

    /** Too far away in time to be the same event, so it is left alone and a new row is written. */
    @Test
    fun `a payment days earlier is not adopted`() {
        seed(
            "old", 5_000_00L, TxnType.DEBIT, "3062", "Kerala Gramin Bank",
            occurredAt = at - 5L * 24 * 3_600_000L,
        )

        move(kgb, icici, 5_000_00L)

        assertThat(live()).hasSize(3)
        assertThat(live().first { it.id == "old" }.transferPeerId).isNull()
    }

    // ── The CRED case ─────────────────────────────────────────────────────────────────

    /**
     * The household's own figures: ₹468.41 settled the card, ₹425.41 left the bank, ₹43 was
     * points. Each amount is recorded against the account it actually moved on, and the gap is
     * stored nowhere — it is neither spending nor income nor a transfer.
     */
    @Test
    fun `points paying part of a bill leaves two rows and no third entry`() {
        assertThat(move(kgb, icici, out = 425_41L, into = 468_41L)).isTrue()

        val rows = live()
        assertThat(rows).hasSize(2)
        assertThat(leg("3062", null)!!.amountPaise).isEqualTo(425_41L)
        assertThat(leg("3008", null)!!.amountPaise).isEqualTo(468_41L)
        // Nothing anywhere holds the 43.
        assertThat(rows.none { it.amountPaise == 43_00L }).isTrue()
    }

    // ── Guards ────────────────────────────────────────────────────────────────────────

    /** The same place twice is not a move, and must not be stored as one. */
    @Test
    fun `a move to the same account is refused`() {
        assertThat(move(kgb, kgb, 5_000_00L)).isFalse()
        assertThat(live()).isEmpty()
    }

    /** Two different labels for one account are still one account — the key is what counts. */
    @Test
    fun `the same account under a different label is still refused`() {
        val relabelled = MoveSide("3062", "Kerala Gramin Bank", "Main account")
        assertThat(move(kgb, relabelled, 5_000_00L)).isFalse()
        assertThat(live()).isEmpty()
    }

    @Test
    fun `a zero or negative amount is refused`() {
        assertThat(move(kgb, icici, out = 0L)).isFalse()
        assertThat(move(kgb, icici, out = 5_000_00L, into = 0L)).isFalse()
        assertThat(move(kgb, icici, out = -100L)).isFalse()
        assertThat(live()).isEmpty()
    }

    /**
     * An end with neither digits nor a name cannot be recorded.
     *
     * `accountBalances()` opens by discarding rows with neither, so such a leg would not merely
     * be unlabelled — it would be invisible, and the money would appear to have evaporated.
     */
    @Test
    fun `an unidentifiable end is refused`() {
        assertThat(move(kgb, MoveSide(null, null, "Somewhere"), 5_000_00L)).isFalse()
        assertThat(live()).isEmpty()
    }

    // ── Cash, cards both ways, and put-aside ──────────────────────────────────────────

    /** An ATM withdrawal: an account to a pocket, and not a purchase. */
    @Test
    fun `taking cash out is recorded as a move`() {
        assertThat(move(kgb, cash, 2_000_00L)).isTrue()
        assertThat(live()).hasSize(2)
        assertThat(MonthlyAggregator.totalSpent(live().map { it.toDomain() })).isEqualTo(0L)
        assertThat(leg(null, "Cash")!!.type).isEqualTo(TxnType.CREDIT.name)
    }

    /** Cash back into an account is the same event in the other direction. */
    @Test
    fun `paying cash into an account works the other way round`() {
        assertThat(move(cash, kgb, 2_000_00L)).isTrue()
        assertThat(leg(null, "Cash")!!.type).isEqualTo(TxnType.DEBIT.name)
        assertThat(leg("3062", null)!!.type).isEqualTo(TxnType.CREDIT.name)
    }

    /**
     * A card as the *source* — a cash advance. Rare, and worth pinning because the card branch
     * of `accountBalances` treats a `SELF_TRANSFER` as settling, so the debt would fall when it
     * should rise. It does; this records the behaviour rather than endorsing it.
     */
    @Test
    fun `a move out of a card is recorded, and is the case the card rule reads as settling`() {
        assertThat(move(icici, kgb, 1_000_00L)).isTrue()
        val fromCard = leg("3008", null)!!
        assertThat(fromCard.type).isEqualTo(TxnType.DEBIT.name)
        assertThat(fromCard.category).isEqualTo(Category.SELF_TRANSFER.name)
    }

    // ── Deleting ──────────────────────────────────────────────────────────────────────

    /**
     * Half a move is not a smaller truth. Deleting the bank's debit alone would leave the
     * arriving leg still reducing a card's outstanding with nothing having paid for it.
     */
    @Test
    fun `deleting one leg deletes the other`() {
        move(kgb, icici, 5_000_00L)
        val first = live().first()

        runBlocking { repo.deleteOrRequest(first.id) }

        assertThat(live()).isEmpty()
    }

    /** From either end — the link is symmetric and the behaviour has to be too. */
    @Test
    fun `deleting the arriving leg also removes the one that left`() {
        move(kgb, icici, 5_000_00L)
        val arriving = leg("3008", null)!!

        runBlocking { repo.deleteOrRequest(arriving.id) }

        assertThat(live()).isEmpty()
    }

    /** A member who is not the owner asks, and asks about both — the pair decides together. */
    @Test
    fun `a non-owner's delete requests both legs`() {
        move(kgb, icici, 5_000_00L)
        runBlocking { prefs.setHouseholdOwner(false) }

        val approved = runBlocking { repo.deleteOrRequest(live().first().id) }

        assertThat(approved).isFalse()
        assertThat(live()).hasSize(2)
        assertThat(live().all { it.deleteRequestedBy == "manuel" }).isTrue()
    }

    // ── Sync ──────────────────────────────────────────────────────────────────────────

    /**
     * Both legs must reach the other phone carrying the link.
     *
     * Sync is last-write-wins per row, so a link that travelled on only one of them would leave
     * the partner's phone with a pair where one half is a transfer and the other is spending —
     * and her month would be wrong by the size of the move.
     */
    @Test
    fun `both legs are logged for sync with the link on them`() {
        move(kgb, icici, 5_000_00L)

        val events = runBlocking { db.syncEventDao().all() }
        val ids = live().map { it.id }.toSet()
        assertThat(events.map { it.txnId }.toSet()).isEqualTo(ids)
        assertThat(events.all { it.payloadJson!!.contains("transferPeerId") }).isTrue()
        for (row in live()) {
            val payload = events.first { it.txnId == row.id }.payloadJson!!
            assertThat(payload).contains("\"transferPeerId\":\"${row.transferPeerId}\"")
        }
    }

    // ── Interaction with the mechanism it replaces ────────────────────────────────────

    /**
     * `markSelfTransfers` pairs an equal debit and credit on two accounts by inference. It must
     * leave a stated move alone — both legs are already `SELF_TRANSFER`, so it skips them, and
     * it must not repoint the link it did not create.
     */
    @Test
    fun `the amount-matching pass leaves a stated move untouched`() {
        move(kgb, icici, 5_000_00L)
        val before = live().associate { it.id to it.transferPeerId }

        val marked = runBlocking { repo.markSelfTransfers() }

        assertThat(marked).isEqualTo(0)
        assertThat(live().associate { it.id to it.transferPeerId }).isEqualTo(before)
    }

    // ── The other ordering: the move is stated before the message arrives ─────────────

    /**
     * Adoption covers "SMS first, statement second". This is the reverse, and it has to be the
     * *dedup* path rather than the adoption one, because by the time the message arrives the
     * move has already written its row.
     *
     * If this ever imports a third row, every transfer recorded a moment before the bank's text
     * lands doubles the money leaving the account.
     */
    @Test
    fun `a bank message arriving after the move is stated is not imported twice`() = runBlocking {
        val body = "Debited Rs 5000 from a/c XX4657 on 02JUL2026 07:20:07.Bal Rs 3572.55. " +
            "-Federal Bank"
        val delivered = 1_782_000_000_000L
        val parsed = (SmsParser().parse("AD-FEDBNK", body, delivered)
            as SmsParser.Result.Expense).txn

        // Stated at the moment it happened, which is what somebody recording it live would do.
        assertThat(move(federal, icici, 5_000_00L, occurredAt = parsed.occurredAt)).isTrue()
        assertThat(live()).hasSize(2)

        assertThat(repo.ingestParsed(parsed)).isNull()
        assertThat(live()).hasSize(2)
    }

    /**
     * The same, for the card issuer's acknowledgement — which is the message most likely to
     * arrive late, because it carries a date and no clock time and so lands most of a day away.
     *
     * It is caught by the card-bill echo rule rather than the three-minute window, and that rule
     * ignores direction, which is what lets it match a hand-written credit against a message
     * that parses as a debit.
     */
    @Test
    fun `a card acknowledgement arriving after the move is stated is not imported twice`() =
        runBlocking {
            val body = "Payment of Rs 468.41 has been received on your ICICI Bank Credit Card " +
                "XX3008 through Bharat Bill Payment System."
            val delivered = 1_782_000_000_000L
            val result = SmsParser().parse("VM-ICICIT", body, delivered)
            // If the parser stops reading this shape the test is meaningless rather than
            // passing, so say so out loud.
            assertThat(result).isInstanceOf(SmsParser.Result.Expense::class.java)
            val parsed = (result as SmsParser.Result.Expense).txn

            move(kgb, icici, out = 425_41L, into = 468_41L, occurredAt = parsed.occurredAt)
            assertThat(live()).hasSize(2)

            assertThat(repo.ingestParsed(parsed)).isNull()
            assertThat(live()).hasSize(2)
        }

    // ── Dates ─────────────────────────────────────────────────────────────────────────

    /**
     * A move backdated into a previous month lands in that month, on both legs.
     *
     * Both rows take the stated time rather than "now", so a bill remembered three days late
     * does not move a closed month's totals into the open one.
     */
    @Test
    fun `a backdated move puts both legs on the stated date`() {
        val lastMonth = at - 40L * 24 * 3_600_000L
        move(kgb, sbi, 10_000_00L, occurredAt = lastMonth)
        assertThat(live().map { it.occurredAt }.toSet()).containsExactly(lastMonth)
    }

    /**
     * A note travels onto rows the move creates, and does not overwrite one already there.
     *
     * An existing note was typed by somebody about that same payment; it is not this action's
     * to discard.
     */
    @Test
    fun `a note is written to new legs and does not clobber an existing one`() {
        seed("sms", 5_000_00L, TxnType.DEBIT, "3062", "Kerala Gramin Bank")
        runBlocking { repo.setNote("sms", "paid at the branch") }

        move(kgb, icici, 5_000_00L, note = "August bill")

        assertThat(live().first { it.id == "sms" }.note).isEqualTo("paid at the branch")
        assertThat(live().first { it.id != "sms" }.note).isEqualTo("August bill")
    }
}
