package com.manuel.ours.data.repo

import com.manuel.ours.data.db.MerchantRuleDao
import com.manuel.ours.data.db.MerchantRuleEntity
import com.manuel.ours.data.db.SyncEventDao
import com.manuel.ours.data.db.SyncEventEntity
import com.manuel.ours.data.db.TransactionDao
import com.manuel.ours.data.db.toDomain
import com.manuel.ours.data.db.toEntity
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.sms.CategoryPredictor
import com.manuel.ours.data.sms.BankRules
import com.manuel.ours.data.sms.Categorizer
import com.manuel.ours.data.sms.SmsDeduplicator
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.data.sync.LamportClock
import com.manuel.ours.data.sync.SyncOp
import com.manuel.ours.data.sync.SyncPayload
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The only writer to the transactions table.
 *
 * Every mutation does two things atomically-in-spirit: update Room (what the UI reads)
 * and append to this device's outbound log (what sync ships). Anything that skips the
 * second step silently stops syncing, so all writes funnel through here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class TransactionRepository @Inject constructor(
    private val txnDao: TransactionDao,
    private val eventDao: SyncEventDao,
    private val merchantRuleDao: MerchantRuleDao,
    private val prefs: AppPrefs,
    private val clock: LamportClock,
) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /**
     * Every read goes through the tracking-start cutoff.
     *
     * Filtering here rather than in each ViewModel is the whole point: Home, Summary,
     * Activity, Budgets and Sort all derive from these three functions, so one clamp
     * covers them and no future screen can forget to apply it. The rows themselves are
     * left alone — this hides history, it does not destroy it.
     */
    fun observeAll(): Flow<List<Transaction>> =
        combine(txnDao.observeAll(), prefs.trackingStartAt) { list, startAt ->
            list.filter { it.occurredAt >= startAt }.map { it.toDomain() }
        }

    fun observeBetween(from: Long, to: Long): Flow<List<Transaction>> =
        prefs.trackingStartAt.flatMapLatest { startAt ->
            txnDao.observeBetween(maxOf(from, startAt), to)
                .map { list -> list.map { it.toDomain() } }
        }

    suspend fun getBetween(from: Long, to: Long): List<Transaction> {
        val startAt = prefs.trackingStartAtOnce()
        return txnDao.getBetween(maxOf(from, startAt), to).map { it.toDomain() }
    }

    fun observeNeedsReviewCount(): Flow<Int> = txnDao.observeNeedsReviewCount()

    fun observeNeedsReview(): Flow<List<Transaction>> =
        txnDao.observeNeedsReview().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: String): Transaction? = txnDao.getById(id)?.toDomain()

    fun observeById(id: String): Flow<Transaction?> =
        txnDao.observeById(id).map { it?.toDomain() }

    /**
     * Ingests one parsed SMS. Returns the stored transaction, or null when it was a
     * duplicate of one we already have.
     */
    suspend fun ingestParsed(
        parsed: SmsParser.ParsedTxn,
        source: TxnSource = TxnSource.SMS,
    ): Transaction? {
        // Dedup: the bank and the UPI app both texted us about one payment.
        for (key in SmsDeduplicator.candidateKeys(
            parsed.amountPaise, parsed.dedupeAt, parsed.refNo,
        )) {
            val existing = txnDao.findByDedupeKey(key) ?: continue
            // Compare stored dedupeAt against incoming dedupeAt. Falling back to
            // occurredAt here is what let a rescan duplicate the whole history.
            if (!SmsDeduplicator.isDuplicate(existing, parsed, existing.dedupeAt)) continue
            if (SmsDeduplicator.richer(existing, parsed)) {
                val merged = existing.copy(
                    merchant = parsed.merchant ?: existing.merchant,
                    refNo = parsed.refNo ?: existing.refNo,
                    accountTail = parsed.accountTail ?: existing.accountTail,
                )
                saveAndLog(merged.toDomain(), merged.dedupeKey, merged.dedupeAt)
            }
            return null
        }

        // Nothing from before the cutoff is stored at all. Without this a live SMS
        // carrying an old in-body date would slip past the backfill window and reappear
        // in a month the user has explicitly retired.
        val startAt = prefs.trackingStartAtOnce()
        if (parsed.occurredAt < startAt) return null

        val rules = merchantRuleDao.all()
        val self = prefs.snapshot()

        // The parser's Kind wins over merchant matching: a card bill payment must not
        // be categorised by whatever its text happens to look like, or it silently
        // rejoins the spend total it was excluded from.
        val category = when (parsed.kind) {
            SmsParser.Kind.CARD_BILL_PAYMENT -> Category.CARD_PAYMENT
            SmsParser.Kind.SAVINGS_DEPOSIT -> Category.INVESTMENTS
            SmsParser.Kind.TRANSFER -> Category.TRANSFERS
            SmsParser.Kind.PURCHASE -> Categorizer.categorize(parsed.merchant, parsed.type, rules)
        }

        val txn = Transaction(
            id = UUID.randomUUID().toString(),
            amountPaise = parsed.amountPaise,
            type = parsed.type,
            // A *credit* that names no payer gets labelled with the bank that received
            // it. Banks routinely credit an account without saying who sent the money
            // ("Your A/c XXXXnnnn credited Rs.NN,NNN Bal after txn ..."), and on that row
            // "Unknown payee" tells you nothing you did not already know, while the bank
            // name at least says where to go and look.
            //
            // This is still a fallback and not a real merchant, so [recategorize]
            // refuses to learn a rule from it — otherwise sorting one salary would teach
            // the app that everything that bank ever credits is salary.
            //
            // Debits keep the placeholder. For money going out the payee is the whole
            // question, and answering it with your own bank's name would be misleading
            // rather than merely unhelpful.
            merchant = parsed.merchant
                ?: parsed.bank?.takeIf { parsed.type == TxnType.CREDIT }
                ?: UNKNOWN_PAYEE,
            category = category,
            occurredAt = parsed.occurredAt,
            accountTail = parsed.accountTail,
            refNo = parsed.refNo,
            bank = parsed.bank,
            splitType = SplitType.SHARED,
            source = source,
            ownerUid = self.selfUid ?: "local",
            ownerName = self.selfName ?: "Me",
            // Only debits are worth reviewing. Banks routinely credit an account
            // without naming the sender ("Rs.22 credited to your A/c") — flagging
            // those would bury the handful of debits that genuinely need a decision
            // under fifty pieces of unattributable income.
            needsReview = (parsed.type == TxnType.DEBIT && parsed.merchant == null) ||
                (parsed.kind == SmsParser.Kind.PURCHASE && category == Category.OTHER),
            rawSms = parsed.rawBody,
        )
        val dedupeKey = SmsDeduplicator.bucketKey(
            parsed.amountPaise, parsed.dedupeAt, parsed.refNo,
        )
        saveAndLog(txn, dedupeKey, parsed.dedupeAt)
        return txn
    }

    suspend fun addManual(
        amountPaise: Long,
        type: TxnType,
        merchant: String,
        category: Category,
        occurredAt: Long,
        note: String?,
        splitType: SplitType,
    ): Transaction {
        val self = prefs.snapshot()
        val txn = Transaction(
            id = UUID.randomUUID().toString(),
            amountPaise = amountPaise,
            type = type,
            merchant = merchant,
            category = category,
            occurredAt = occurredAt,
            note = note,
            splitType = splitType,
            source = TxnSource.MANUAL,
            ownerUid = self.selfUid ?: "local",
            ownerName = self.selfName ?: "Me",
            needsReview = false,
        )
        saveAndLog(txn, "manual:${txn.id}", txn.occurredAt)
        return txn
    }

    /**
     * Recategorization also writes a userDefined merchant rule, so the same merchant
     * lands correctly next time without asking again.
     */
    suspend fun recategorize(txnId: String, category: Category, learn: Boolean = true) {
        val existing = txnDao.getById(txnId) ?: return
        val updated = existing.copy(category = category.name, needsReview = false)
        saveAndLog(updated.toDomain(), existing.dedupeKey, existing.dedupeAt)

        // Never learn from a fallback label. A rule for "unknown payee" would apply to
        // every future unnamed debit, and a rule for "Kerala Gramin Bank" would apply to
        // every future unnamed credit from that bank — so categorising one salary would
        // silently relabel every refund and transfer that follows it.
        if (learn && existing.merchant != UNKNOWN_PAYEE && !BankRules.isBankName(existing.merchant)) {
            setMerchantRule(existing.merchant, category)
        }
    }

    suspend fun setSplitType(txnId: String, splitType: SplitType) {
        val existing = txnDao.getById(txnId) ?: return
        saveAndLog(
            existing.copy(splitType = splitType.name).toDomain(),
            existing.dedupeKey,
            existing.dedupeAt,
        )
    }

    suspend fun updateTransaction(txn: Transaction) {
        val existing = txnDao.getById(txn.id)
        saveAndLog(
            txn,
            existing?.dedupeKey ?: "manual:${txn.id}",
            existing?.dedupeAt ?: txn.occurredAt,
        )
    }

    suspend fun delete(txnId: String) {
        val existing = txnDao.getById(txnId) ?: return
        val lamport = clock.tick()
        val deviceId = prefs.deviceId()
        txnDao.softDelete(txnId, lamport, deviceId)
        prefs.writeLamport(lamport)

        eventDao.append(
            SyncEventEntity(
                eventId = UUID.randomUUID().toString(),
                txnId = txnId,
                op = SyncOp.DELETE.name,
                lamport = lamport,
                deviceId = deviceId,
                ownerUid = existing.ownerUid,
                payloadJson = null,
                wallClock = System.currentTimeMillis(),
                pushed = false,
            )
        )
    }

    /**
     * Brings back a soft-deleted transaction.
     *
     * Writes a fresh UPSERT rather than clearing the tombstone: the delete may already
     * have reached the other phone, and the merge resolves by highest Lamport value.
     * A higher-numbered upsert supersedes the delete everywhere; editing the old row
     * in place would lose the race and the transaction would vanish again on sync.
     */
    suspend fun restore(txnId: String) {
        val existing = txnDao.getByIdIncludingDeleted(txnId) ?: return
        saveAndLog(
            existing.toDomain().copy(deleted = false),
            existing.dedupeKey,
            existing.dedupeAt,
        )
    }

    /** Room write + outbound log append. Never call one without the other. */
    private suspend fun saveAndLog(txn: Transaction, dedupeKey: String, dedupeAt: Long) {
        val lamport = clock.tick()
        val deviceId = prefs.deviceId()

        txnDao.upsert(txn.toEntity(dedupeKey, lamport, deviceId, dedupeAt))
        prefs.writeLamport(lamport)

        val payload = SyncPayload(
            amountPaise = txn.amountPaise,
            type = txn.type.name,
            merchant = txn.merchant,
            category = txn.category.name,
            occurredAt = txn.occurredAt,
            accountTail = txn.accountTail,
            refNo = txn.refNo,
            bank = txn.bank,
            note = txn.note,
            splitType = txn.splitType.name,
            source = txn.source.name,
            ownerName = txn.ownerName,
            needsReview = txn.needsReview,
            rawSms = txn.rawSms,
        )
        eventDao.append(
            SyncEventEntity(
                eventId = UUID.randomUUID().toString(),
                txnId = txn.id,
                op = SyncOp.UPSERT.name,
                lamport = lamport,
                deviceId = deviceId,
                ownerUid = txn.ownerUid,
                payloadJson = json.encodeToString(SyncPayload.serializer(), payload),
                wallClock = System.currentTimeMillis(),
                pushed = false,
            )
        )
    }

    /**
     * Claims any transactions imported before the user's identity existed. Safe to
     * call on every launch — it is a no-op once there are no orphans left.
     */
    suspend fun adoptLocalTransactions(): Int {
        val self = prefs.snapshot()
        val uid = self.selfUid ?: return 0
        if (uid == "local") return 0
        if (txnDao.orphanCount() == 0) return 0
        return txnDao.adoptOrphans(uid, self.selfName ?: "Me")
    }

    suspend fun count(): Int = txnDao.count()

    suspend fun latestSmsTimestamp(): Long? = txnDao.latestSmsTimestamp()

    /**
     * Categories to offer as one-tap buttons on the new-expense notification.
     * Bounded history: the last few hundred transactions are plenty to predict from,
     * and this runs on the SMS-received path where a full table scan would be rude.
     */
    suspend fun predictCategories(
        merchant: String,
        amountPaise: Long,
        type: TxnType,
        limit: Int = 3,
    ): List<Category> = CategoryPredictor.predict(
        merchant = merchant,
        amountPaise = amountPaise,
        type = type,
        history = txnDao.recent(PREDICTION_HISTORY).map { it.toDomain() },
        rules = merchantRuleDao.all(),
        limit = limit,
    )

    /**
     * Relabels credits already stored as "Unknown payee" with the bank that received
     * them, and returns how many were changed.
     *
     * A rescan cannot do this on its own: the deduplicator recognises these rows as
     * already-seen and returns before the merchant is ever reconsidered, so without a
     * one-shot repair the improvement would only ever apply to future messages and the
     * existing history would stay unreadable.
     *
     * Safe to run repeatedly — it only touches rows that still hold the placeholder,
     * so once they are relabelled it does nothing.
     */
    suspend fun relabelBareCredits(): Int {
        val stale = txnDao.getBetween(0, Long.MAX_VALUE)
            .filter { it.type == TxnType.CREDIT.name && it.merchant == UNKNOWN_PAYEE }
            .filter { !it.bank.isNullOrBlank() }
        for (row in stale) {
            saveAndLog(
                row.copy(merchant = row.bank!!).toDomain(),
                row.dedupeKey,
                row.dedupeAt,
            )
        }
        return stale.size
    }

    /**
     * Rebuilds this device's outbound log from the transactions table.
     *
     * "Re-upload everything" cannot simply un-mark events as unsent, because there may
     * be no events left to un-mark: compaction deletes superseded entries once pushed,
     * and until recently it deleted rather more than that. The transactions table is the
     * source of truth and survives all of it, so the log is regenerated from there.
     *
     * Returns how many events were minted.
     */
    suspend fun rebuildOwnLog(): Int {
        val transactions = txnDao.getBetween(0, Long.MAX_VALUE).filterNot { it.deleted }
        // Drop the old log first, or every transaction ends up with two events saying
        // the same thing and the sheet grows a duplicate of its entire history.
        eventDao.deleteAll()
        for (row in transactions) {
            saveAndLog(row.toDomain(), row.dedupeKey, row.dedupeAt)
        }
        return transactions.size
    }

    suspend fun seedMerchantRulesIfNeeded() {
        if (merchantRuleDao.count() > 0) return
        merchantRuleDao.insertAll(Categorizer.seedEntities())
    }

    fun observeMerchantRules(): Flow<List<MerchantRuleEntity>> = merchantRuleDao.observeAll()

    /**
     * Files every future expense whose payee contains [pattern] under [category].
     *
     * Replaces any existing rule for the same text rather than adding a second one.
     * `@Upsert` on an auto-generated id always *inserts*, so re-categorising the same
     * merchant twice used to stack duplicate rules — harmless individually, but the
     * matcher orders by pattern length and then by insertion, so which of the
     * duplicates won became a matter of luck.
     *
     * Patterns under three characters are refused: "in" or "a" appear inside half the
     * merchant names in the country and would silently capture everything.
     */
    suspend fun setMerchantRule(pattern: String, category: Category) {
        val clean = pattern.lowercase().trim()
        if (clean.length < MIN_RULE_LENGTH) return
        merchantRuleDao.all()
            .filter { it.pattern == clean }
            .forEach { merchantRuleDao.delete(it.id) }
        merchantRuleDao.upsert(
            MerchantRuleEntity(pattern = clean, category = category.name, userDefined = true)
        )
    }

    @Deprecated("Use setMerchantRule, which replaces instead of duplicating.")
    suspend fun upsertMerchantRule(pattern: String, category: Category) =
        setMerchantRule(pattern, category)

    suspend fun deleteMerchantRule(id: Long) = merchantRuleDao.delete(id)

    companion object {
        /** Shown when the bank's message names no payee at all. */
        const val UNKNOWN_PAYEE = "Unknown payee"
        const val MIN_RULE_LENGTH = 3

        /** How far back the category predictor looks. */
        private const val PREDICTION_HISTORY = 400
    }
}
