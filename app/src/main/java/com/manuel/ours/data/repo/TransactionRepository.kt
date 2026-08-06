package com.manuel.ours.data.repo

import com.manuel.ours.data.db.MerchantRuleDao
import com.manuel.ours.data.db.SharedRuleEntity
import com.manuel.ours.data.db.MerchantRuleEntity
import com.manuel.ours.data.db.SyncEventDao
import com.manuel.ours.data.db.SyncEventEntity
import com.manuel.ours.data.db.TransactionDao
import com.manuel.ours.data.db.TransactionEntity
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
import com.manuel.ours.domain.ReuploadTally
import com.manuel.ours.domain.Trash
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.ManualBalance
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    private val sharedRuleDao: com.manuel.ours.data.db.SharedRuleDao,
    private val parser: com.manuel.ours.data.sms.SmsParser,
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
        //
        // Candidates are gathered by value — same amount inside the window, plus
        // anything sharing the UPI reference. The old lookup asked for one bucket key,
        // and that key was built from the reference when a message carried one and from
        // the amount and minute when it did not, so the two halves of a pair were
        // filed under different keys and neither could find the other. On a real
        // ledger that left 25 clusters of the same payment recorded two to four times.
        // A card bill produces two messages from two banks: the account says it paid,
        // the card says it was paid. They are one payment, and they are never close
        // together in the app's eyes — the card's acknowledgement carries a date and no
        // clock time, so it lands at midnight, most of a day from the debit it echoes.
        //
        // The window widens only for that case, and only far enough to cover a calendar
        // day. Nothing else about the match is relaxed: same amount to the paise, and
        // the account-or-reference agreement below still applies.
        val window = if (parsed.kind == SmsParser.Kind.CARD_BILL_PAYMENT) {
            SmsDeduplicator.CARD_BILL_WINDOW_MS
        } else {
            SmsDeduplicator.WINDOW_MS
        }
        val candidates = buildList {
            parsed.refNo?.takeIf { it.isNotBlank() }?.let { ref ->
                txnDao.findByRef(ref)?.let(::add)
            }
            addAll(
                txnDao.findNearby(
                    parsed.amountPaise,
                    parsed.dedupeAt - window,
                    parsed.dedupeAt + window,
                )
            )
        }.distinctBy { it.id }

        for (existing in candidates) {
            // Compare stored dedupeAt against incoming dedupeAt. Falling back to
            // occurredAt here is what let a rescan duplicate the whole history.
            val cardBillPair = parsed.kind == SmsParser.Kind.CARD_BILL_PAYMENT ||
                existing.category == Category.CARD_PAYMENT.name
            val matches = if (cardBillPair) {
                SmsDeduplicator.isCardBillEcho(existing, parsed)
            } else {
                SmsDeduplicator.isDuplicate(existing, parsed, existing.dedupeAt)
            }
            if (!matches) continue
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
        val namedAccounts = namedAccounts()
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
            // A name the household has given this account outranks the placeholder.
            // The bank will never name a mother, a landlord or one's own second
            // account, but the household can — once — and the number is what carries
            // that name forward to every payment after it.
            merchant = parsed.merchant
                ?: parsed.counterpartyTail?.let { namedAccounts[it] }
                ?: parsed.bank?.takeIf { parsed.type == TxnType.CREDIT }
                ?: UNKNOWN_PAYEE,
            category = category,
            occurredAt = parsed.occurredAt,
            accountTail = parsed.accountTail,
            counterpartyTail = parsed.counterpartyTail,
            balancePaise = parsed.balancePaise,
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
            needsReview = (parsed.type == TxnType.DEBIT && parsed.merchant == null &&
                parsed.counterpartyTail?.let { namedAccounts[it] } == null) ||
                (parsed.kind == SmsParser.Kind.PURCHASE && category == Category.OTHER),
            rawSms = parsed.rawBody,
        )
        val dedupeKey = SmsDeduplicator.bucketKey(
            parsed.amountPaise, parsed.dedupeAt, parsed.refNo,
        )
        saveAndLog(txn, dedupeKey, parsed.dedupeAt)
        return txn
    }

    /**
     * Accounts the household has named, keyed by the digits the bank shows.
     *
     * Stored as shared rules so a name given on one phone reaches the other through the
     * sheet — the same path bank senders and merchant categories already travel. Naming
     * "8891" as Mother once should not have to be done twice.
     */
    suspend fun namedAccounts(): Map<String, String> =
        sharedRuleDao.ofType(TYPE_ACCOUNT).associate { it.ruleKey to it.value }

    /** Relabels rows for an account whose name is already known. */
    suspend fun applyAccountName(tail: String, name: String) {
        txnDao.allLive()
            .filter { it.counterpartyTail == tail && it.merchant == UNKNOWN_PAYEE }
            .forEach { row ->
                saveAndLog(
                    row.copy(merchant = name, needsReview = false).toDomain(),
                    row.dedupeKey,
                    row.dedupeAt,
                )
            }
    }

    /** Names an account, so every future payment to it carries the name. */
    /**
     * Records what somebody says is in an account, for the banks that never quote it.
     *
     * Written as a shared rule so it reaches the other phone: the household has one set
     * of accounts, and a figure only one of them knows is a figure the other is missing.
     * The timestamp is the point — it is what lets a real bank balance outrank this the
     * moment one arrives, without anybody having to clear it.
     */
    suspend fun setAccountBalance(key: String, paise: Long?, bank: String?) {
        if (key.isBlank()) return
        // Null means "forget what I typed": the rule is kept but emptied, so the reader
        // skips it and whatever the bank last said takes over again.
        //
        // Zero used to mean that too, which left a zero-balance account with no way to
        // say so — typing 0 erased the entry instead of recording it. An empty string is
        // the tombstone; "0" is a figure like any other.
        // Negatives are stored, not clamped. Coercing them to 0 would report an overdrawn
        // account as merely empty — the one direction a balance must never be wrong in,
        // since "safe to spend" is computed off it. Nothing can type one today; this is
        // so that when something can, it does not quietly round the debt away.
        val amount = paise?.toString().orEmpty()
        sharedRuleDao.upsertAll(
            listOf(
                SharedRuleEntity(
                    type = TYPE_BALANCE,
                    ruleKey = key,
                    // bank alongside the figure, so an account known only by a typed
                    // balance still has a name to show.
                    // amount | bank | who typed it
                    value = "$amount|${bank.orEmpty()}|${prefs.selfUid.first().orEmpty()}",
                    updatedAt = System.currentTimeMillis(),
                    deviceId = prefs.deviceId(),
                )
            )
        )
    }

    /** The floor each account must not go under, as the household has recorded it. */
    suspend fun setAccountMinimum(key: String, paise: Long) {
        if (key.isBlank()) return
        sharedRuleDao.upsertAll(
            listOf(
                SharedRuleEntity(
                    type = TYPE_MIN_BALANCE,
                    ruleKey = key,
                    value = paise.toString(),
                    updatedAt = System.currentTimeMillis(),
                    deviceId = prefs.deviceId(),
                )
            )
        )
    }

    fun observeAccountMinimums(): kotlinx.coroutines.flow.Flow<Map<String, Long>> =
        sharedRuleDao.observeOfType(TYPE_MIN_BALANCE).map { rules ->
            rules.mapNotNull { r -> r.value.toLongOrNull()?.let { r.ruleKey to it } }.toMap()
        }

    /**
     * What every account is known to hold, in one place.
     *
     * Defined here rather than in each ViewModel because two screens were computing it
     * from different inputs and could not help but disagree: Summary fed it two years of
     * member-*filtered* rows, Home would have fed it two months of unfiltered ones, and
     * the same account could then show a different figure on each — with no way for
     * anyone to tell which was right.
     *
     * Deliberately **not** member-filtered. A bank account is a property of the
     * household, not of the Both/Me/Partner chip; running the chip over it meant that
     * viewing "Me" silently dropped a partner's accounts out of a panel still headed
     * "What is left", which reads as a household total and would then quietly be one
     * person's. Who may see which account is decided by [isOwner] and [viewerUid], which
     * is the rule that was actually designed for it.
     */
    fun observeBalances(
        viewerUid: String,
        isOwner: Boolean,
    ): kotlinx.coroutines.flow.Flow<List<com.manuel.ours.domain.model.AccountBalance>> =
        combine(
            observeAll(),
            observeManualBalances(),
            observeAccountMinimums(),
        ) { all, manual, minimums ->
            com.manuel.ours.domain.MonthlyAggregator.accountBalances(
                transactions = all,
                manual = manual,
                minimums = minimums,
                viewerUid = viewerUid,
                isOwner = isOwner,
            )
        }

    /** Hand-entered balances, keyed by account, as the household currently has them. */
    fun observeManualBalances(): kotlinx.coroutines.flow.Flow<Map<String, ManualBalance>> =
        sharedRuleDao.observeOfType(TYPE_BALANCE).map { rules ->
            rules.mapNotNull { rule ->
                val parts = rule.value.split('|')
                // An empty amount means "this account exists and nobody has said what is
                // in it". Dropping the row instead would take the account with it, along
                // with the record of who added it. A present "0" is a real zero, and has
                // to stay distinguishable from the empty case.
                val paise = parts.firstOrNull()?.takeIf(String::isNotBlank)?.toLongOrNull()
                rule.ruleKey to ManualBalance(
                    paise = paise,
                    setAt = rule.updatedAt,
                    bank = parts.getOrNull(1)?.takeIf(String::isNotBlank),
                    ownerUid = parts.getOrNull(2)?.takeIf(String::isNotBlank),
                )
            }.toMap()
        }

    suspend fun nameAccount(tail: String, name: String) {
        val clean = name.trim()
        if (tail.isBlank() || clean.isEmpty()) return
        sharedRuleDao.upsertAll(
            listOf(
                SharedRuleEntity(
                    type = TYPE_ACCOUNT,
                    ruleKey = tail,
                    value = clean,
                    updatedAt = System.currentTimeMillis(),
                    deviceId = prefs.deviceId(),
                )
            )
        )
        // Apply it to what is already there, so naming an account fixes the history it
        // came from rather than only the next payment.
        txnDao.allLive()
            .filter { it.counterpartyTail == tail && it.merchant == UNKNOWN_PAYEE }
            .forEach { row ->
                saveAndLog(
                    row.copy(merchant = clean, needsReview = false).toDomain(),
                    row.dedupeKey,
                    row.dedupeAt,
                )
            }
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

    /** Blank clears it, so a row returns to offering the invitation rather than a box. */
    suspend fun setNote(txnId: String, note: String) {
        val existing = txnDao.getById(txnId) ?: return
        val clean = note.trim().takeIf { it.isNotEmpty() }
        if (existing.note == clean) return
        saveAndLog(existing.copy(note = clean).toDomain(), existing.dedupeKey, existing.dedupeAt)
    }

    suspend fun setSplitType(txnId: String, splitType: SplitType) {
        val existing = txnDao.getById(txnId) ?: return
        saveAndLog(
            existing.copy(splitType = splitType.name).toDomain(),
            existing.dedupeKey,
            existing.dedupeAt,
        )
    }

    /**
     * Renames the payee on one row.
     *
     * Banks routinely name no payee at all — a Kerala Gramin UPI debit says only
     * "credited to a/c no. XXXX" — and no amount of parsing can recover a name that was
     * never sent. Ninety-six rows on the first real ledger said "Unknown payee", and the
     * person holding the phone is the only one who knows who they paid.
     *
     * Renaming does *not* teach a merchant rule. The placeholder is shared by every
     * unnamed debit in the household, so learning from one would file every future
     * anonymous payment under whatever this one turned out to be.
     */
    suspend fun rename(txnId: String, merchant: String): Boolean {
        val clean = merchant.trim()
        if (clean.isEmpty()) return false
        val existing = txnDao.getById(txnId) ?: return false
        if (existing.merchant == clean) return false
        saveAndLog(
            existing.copy(merchant = clean, needsReview = false).toDomain(),
            existing.dedupeKey,
            existing.dedupeAt,
        )
        return true
    }

    /**
     * Overwrites the amount and stamps the row as hand-edited.
     *
     * Gated on the household owner *and* developer mode, and both are checked here
     * rather than only in the UI — a capability guarded solely by which screen you can
     * reach is not guarded at all.
     *
     * The stamp is the point. An edited figure no longer reconciles against the bank
     * message it came from, and the app has no way to re-derive the original once it is
     * gone, so the row says plainly that a person changed it.
     */
    suspend fun editAmount(txnId: String, amountPaise: Long): Boolean {
        if (amountPaise <= 0) return false
        if (!prefs.householdOwnerOnce()) return false
        if (!prefs.developerMode.first()) return false

        val existing = txnDao.getById(txnId) ?: return false
        if (existing.amountPaise == amountPaise) return false
        saveAndLog(
            existing.copy(
                amountPaise = amountPaise,
                amountEditedAt = System.currentTimeMillis(),
            ).toDomain(),
            existing.dedupeKey,
            existing.dedupeAt,
        )
        return true
    }

    suspend fun updateTransaction(txn: Transaction) {
        val existing = txnDao.getById(txn.id)
        saveAndLog(
            txn,
            existing?.dedupeKey ?: "manual:${txn.id}",
            existing?.dedupeAt ?: txn.occurredAt,
        )
    }

    /**
     * Removes the row, or asks the owner to.
     *
     * A delete is the one change nobody can inspect afterwards: an edit leaves a value
     * to disagree with, a deletion leaves nothing at all. In a shared ledger that makes
     * it the one action worth a second pair of eyes, so a member's delete becomes a
     * request and the row stays visible — and counted — until the owner decides.
     *
     * The owner's own delete is immediate. Asking yourself for permission is theatre.
     */
    suspend fun deleteOrRequest(txnId: String): Boolean {
        if (prefs.householdOwnerOnce()) {
            delete(txnId)
            return true
        }
        val existing = txnDao.getById(txnId) ?: return false
        val uid = prefs.snapshot().selfUid ?: return false
        saveAndLog(
            existing.copy(deleteRequestedBy = uid).toDomain(),
            existing.dedupeKey,
            existing.dedupeAt,
        )
        return false
    }

    /** Owner accepts: the row goes, and the tombstone syncs like any other delete. */
    suspend fun approveDelete(txnId: String) = delete(txnId)

    /** Owner declines: the marker clears and the row carries on as though nothing happened. */
    suspend fun rejectDelete(txnId: String) {
        val existing = txnDao.getById(txnId) ?: return
        if (existing.deleteRequestedBy == null) return
        saveAndLog(
            existing.copy(deleteRequestedBy = null).toDomain(),
            existing.dedupeKey,
            existing.dedupeAt,
        )
    }

    fun observeDeleteRequestCount(): Flow<Int> = txnDao.observeDeleteRequestCount()

    fun observeDeleteRequests(): Flow<List<Transaction>> =
        txnDao.observeDeleteRequests().map { list -> list.map { it.toDomain() } }

    suspend fun delete(txnId: String) {
        val existing = txnDao.getById(txnId) ?: return
        val lamport = clock.tick()
        val deviceId = prefs.deviceId()
        txnDao.softDelete(txnId, lamport, deviceId, at = System.currentTimeMillis())
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
     * What Trash holds: deletions from the last [Trash.WINDOW_DAYS] days.
     *
     * Not filtered by the tracking-start cutoff, unlike every other read here. A row
     * deleted yesterday should be recoverable whether or not its month is retired —
     * hiding it because of its *date* would make Trash lie about what it is holding.
     */
    fun observeTrash(): Flow<List<Transaction>> =
        txnDao.observeTrash(Trash.since(System.currentTimeMillis()))
            .map { list -> list.map { it.toDomain() } }

    fun observeTrashCount(): Flow<Int> =
        txnDao.observeTrashCount(Trash.since(System.currentTimeMillis()))

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
            // deletedAt cleared as well as the flag. Leaving the stamp behind would put
            // a live row in Trash's window, so a restored entry would sit in both the
            // ledger and the bin at once — and be "restorable" again from there.
            existing.toDomain().copy(deleted = false, deletedAt = null),
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
            deleteRequestedBy = txn.deleteRequestedBy,
            amountEditedAt = txn.amountEditedAt,
            counterpartyTail = txn.counterpartyTail,
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
        // One shot. This repairs rows imported before bare credits carried their bank's
        // name; once done it can never find anything again, and re-running it on every
        // launch meant decrypting the entire history to prove that.
        if (prefs.bareCreditsRelabelled()) return 0
        val stale = txnDao.creditsWithPlaceholderPayee(UNKNOWN_PAYEE)
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
     * Folds rows owned by an earlier identity of this same person back onto them.
     *
     * Reinstalling mints a new uid. Rows that come back from the sheet still carry the
     * old one, and the household member list is built from distinct owner ids — so the
     * same person appears twice in the filter chips, and one of them owns almost
     * nothing. On the first real phone a single leftover row produced a phantom
     * "Manuel" beside the real one.
     *
     * Matched on the display name, which is the only honest signal available: a
     * different uid with *your* name is you, whereas a different uid with a different
     * name is a genuine second member and must be left alone.
     *
     * Not a one-shot. A future reinstall creates the same situation again, and the
     * query costs nothing when there is nothing to fold.
     */
    suspend fun mergeOwnAliases(): Int {
        val self = prefs.snapshot()
        val uid = self.selfUid ?: return 0
        val name = self.selfName?.trim().orEmpty()
        if (uid == "local" || name.isEmpty()) return 0

        val strays = txnDao.rowsOwnedByAlias(uid, name)
        for (row in strays) {
            saveAndLog(
                row.toDomain().copy(ownerUid = uid, ownerName = name),
                row.dedupeKey,
                row.dedupeAt,
            )
        }
        return strays.size
    }

    /**
     * Reads the destination account out of messages already stored.
     *
     * The column arrived after these rows did, so every payment imported before it
     * carries no identifier — and naming an account would then fix the future while
     * leaving the history exactly as anonymous as before. The original message is kept
     * on the phone, so the answer is already here; it only has to be read again.
     *
     * Writes nothing when the message names no destination, which is most of them.
     */
    /**
     * Fills the balance column from messages already stored.
     *
     * The parser has always read the closing balance and thrown it away, so on the day
     * the column arrives every existing row has none and the screen would open empty —
     * on this household, twenty-four rows of history showing nothing until the next
     * bank text. The figures are already here, in rawSms; this only has to look.
     */
    suspend fun backfillBalances(): Int {
        if (prefs.balancesBackfilled()) return 0
        var filled = 0
        for (row in txnDao.allLive()) {
            if (row.balancePaise != null) continue
            val body = row.rawSms ?: continue
            val balance = parser.extractBalance(body) ?: continue
            saveAndLog(
                row.copy(balancePaise = balance).toDomain(),
                row.dedupeKey,
                row.dedupeAt,
            )
            filled++
        }
        prefs.setBalancesBackfilled()
        return filled
    }

    suspend fun backfillCounterpartyTails(): Int {
        if (prefs.counterpartyBackfilled()) return 0
        var filled = 0
        for (row in txnDao.allLive()) {
            if (row.counterpartyTail != null) continue
            val body = row.rawSms ?: continue
            val tail = parser.extractCounterpartyTail(body) ?: continue
            saveAndLog(
                row.copy(counterpartyTail = tail).toDomain(),
                row.dedupeKey,
                row.dedupeAt,
            )
            filled++
        }
        prefs.setCounterpartyBackfilled()
        return filled
    }

    /**
     * Marks money that only moved between the household's own accounts.
     *
     * The signal is the account tail. This phone receives alerts for accounts the
     * household owns, so the tail stored on a row is always *ours* — a debit carrying
     * one tail and a credit carrying another, for the same amount minutes apart, is
     * money that left one of your accounts and arrived in another. Nothing was earned
     * and nothing was spent.
     *
     * Both legs are marked rather than deleted. They are real bank messages describing
     * real movements, and a household that transferred by accident should be able to
     * see that it happened; what they should not see is the month claiming they spent
     * it. [Category.SELF_TRANSFER] is neutral on both sides of the ledger.
     *
     * Requires two *different*, non-blank tails. Same tail is a reversal, which the
     * parser already rejects upstream, and a missing tail leaves no evidence the two
     * accounts differ — guessing there would eventually pair a real expense with an
     * unrelated credit of the same size.
     */
    suspend fun markSelfTransfers(): Int {
        val live = txnDao.allLive()
        val debits = live.filter {
            it.type == TxnType.DEBIT.name && !it.accountTail.isNullOrBlank()
        }
        val credits = live.filter {
            it.type == TxnType.CREDIT.name && !it.accountTail.isNullOrBlank()
        }

        var marked = 0
        val used = mutableSetOf<String>()
        for (debit in debits) {
            if (debit.category == Category.SELF_TRANSFER.name) continue
            val match = credits.firstOrNull { credit ->
                credit.id !in used &&
                    credit.category != Category.SELF_TRANSFER.name &&
                    credit.amountPaise == debit.amountPaise &&
                    credit.accountTail != debit.accountTail &&
                    kotlin.math.abs(credit.occurredAt - debit.occurredAt) <= SELF_TRANSFER_WINDOW_MS
            } ?: continue

            used += match.id
            for (row in listOf(debit, match)) {
                saveAndLog(
                    row.copy(category = Category.SELF_TRANSFER.name, needsReview = false)
                        .toDomain(),
                    row.dedupeKey,
                    row.dedupeAt,
                )
                marked++
            }
        }
        return marked
    }

    /**
     * Collapses card-bill pairs already in the database.
     *
     * One bill produces two messages from two banks — the account reports paying, the
     * card reports being paid — and the card's arrives with a date and no clock time,
     * so the two sit most of a day apart and no dedupe window could reach them. On the
     * first real ledger that was Rs.7,177.79 counted twice.
     *
     * The debit is kept: money genuinely left the household there, and card purchases
     * never reach this app any other way, so it is the row that belongs in the total.
     * The card's acknowledgement is the echo.
     */
    suspend fun repairCardBillEchoes(): Int {
        if (prefs.cardBillEchoesRepaired()) return 0
        val live = txnDao.allLive()
        val removed = mutableSetOf<String>()

        for (row in live) {
            if (row.id in removed || row.type != TxnType.DEBIT.name) continue
            val echoes = live.filter { other ->
                other.id != row.id &&
                    other.id !in removed &&
                    other.amountPaise == row.amountPaise &&
                    other.bank != row.bank &&
                    other.category == Category.CARD_PAYMENT.name &&
                    kotlin.math.abs(other.dedupeAt - row.dedupeAt) <=
                        SmsDeduplicator.CARD_BILL_WINDOW_MS
            }
            for (echo in echoes) {
                delete(echo.id)
                removed += echo.id
            }
        }
        prefs.setCardBillEchoesRepaired()
        return removed.size
    }

    /**
     * Removes the twelve-hour twins that fixing AM/PM created.
     *
     * Until recently the time capture dropped the meridiem, so a Kerala Gramin
     * "07:49 PM" was stored as 07:49. Correcting that was right, but on an inbox
     * already holding wrongly-timed rows the next scan re-read the same message,
     * arrived at 19:49, and found nothing to deduplicate against — the window is three
     * minutes and the twin was twelve hours away. Every evening transaction already in
     * the database therefore gained a copy.
     *
     * The signature is deliberately narrow: identical to the paise, same bank, same
     * account tail, same calendar day, and separated by twelve hours to within a
     * minute. Two genuine payments matching all of that are vanishingly unlikely, and
     * the cost of being wrong is asymmetric — losing a real transaction is far worse
     * than leaving a duplicate somebody can see and delete.
     *
     * The morning row goes, because the evening one is what the bank actually said.
     */
    suspend fun repairMeridiemTwins(): Int {
        if (prefs.meridiemTwinsRepaired()) return 0
        val twelveHours = 12 * 60 * 60 * 1000L
        val tolerance = 60 * 1000L

        // Compatible, not identical.
        //
        // These pairs are the bank's message and the UPI app's message for one payment,
        // and they never carry the same fields: one names a reference and no account,
        // the other an account and no reference. Demanding equality on both is
        // precisely wrong for the case being repaired. This is the rule
        // SmsDeduplicator already applies at ingest — agree where both know something,
        // ignore where one is silent.
        fun agrees(a: String?, b: String?) = a.isNullOrBlank() || b.isNullOrBlank() || a == b

        val live = txnDao.allLive()
        val removed = mutableSetOf<String>()

        for (row in live) {
            if (row.id in removed) continue
            val twins = live.filter { other ->
                other.id != row.id &&
                    other.id !in removed &&
                    other.amountPaise == row.amountPaise &&
                    other.type == row.type &&
                    other.bank == row.bank &&
                    agrees(other.accountTail, row.accountTail) &&
                    agrees(other.refNo, row.refNo) &&
                    kotlin.math.abs(
                        kotlin.math.abs(other.occurredAt - row.occurredAt) - twelveHours
                    ) <= tolerance
            }
            for (twin in twins) {
                val later = if (twin.occurredAt > row.occurredAt) twin else row
                val earlier = if (twin.occurredAt > row.occurredAt) row else twin
                if (earlier.id in removed) continue

                // Keep the evening row — PM is what the bank said — but take anything
                // the morning copy knew and it does not. Deleting outright would throw
                // away the reference number that only the other message carried.
                val merged = later.copy(
                    refNo = later.refNo ?: earlier.refNo,
                    accountTail = later.accountTail ?: earlier.accountTail,
                    // The two messages describe one payment but carry different facts:
                    // the plain debit has the clock time, the UPI one names the
                    // destination account. Keeping the later row for its time while
                    // dropping the only copy of the account it paid loses the single
                    // thing that could ever give that payment a name.
                    counterpartyTail = later.counterpartyTail ?: earlier.counterpartyTail,
                    merchant = if (later.merchant.equals(UNKNOWN_PAYEE, true)) {
                        earlier.merchant
                    } else later.merchant,
                )
                if (merged != later) {
                    saveAndLog(merged.toDomain(), merged.dedupeKey, merged.dedupeAt)
                }
                delete(earlier.id)
                removed += earlier.id
            }
        }
        prefs.setMeridiemTwinsRepaired()
        return removed.size
    }

    /**
     * Repairs rows whose merchant is an account label rather than a payee.
     *
     * Kerala Gramin words every UPI debit as "credited to a/c no. XXXX", and until the
     * `to` pattern learned to refuse that, the phrase became the merchant. On the first
     * real household it was 189 of 460 rows — all filed under a shop called "a/c no",
     * all stranded in Other because no rule can ever match it, and all inflating one
     * imaginary merchant into the biggest in the ledger.
     *
     * A rescan cannot fix these: dedup recognises the message and returns before the
     * merchant is reconsidered. So it is a one-shot pass over the stored rows, and the
     * honest replacement is the same placeholder an unnamed debit gets today.
     */
    suspend fun repairAccountLabelMerchants(): Int {
        if (prefs.accountLabelsRepaired()) return 0
        val stale = txnDao.withMerchantIn(ACCOUNT_LABELS)
        for (row in stale) {
            saveAndLog(
                row.copy(merchant = UNKNOWN_PAYEE).toDomain(),
                row.dedupeKey,
                row.dedupeAt,
            )
        }
        prefs.setAccountLabelsRepaired()
        return stale.size
    }

    /**
     * Writes rows a restore produced, minting a sync event for each.
     *
     * Deliberately not a bare `upsertAll`. A restore that only touched the local database
     * would leave the other phone holding the un-corrected version forever — the very
     * corrections a backup exists to protect are the ones that would stay lost. Going
     * through [saveAndLog] gives each row a Lamport tick and an outbound event, so the
     * next sync carries them across like any other edit.
     *
     * The cutoff is not applied here. `rebuildOwnLog` honours it because it is rebuilding
     * *the log*; this is recovering data the household chose to keep, and silently
     * dropping half of it on the way back in would be the same silent-cutoff bug in a
     * worse place. What syncs is still bounded by the cutoff at push time.
     */
    suspend fun applyRestore(rows: List<TransactionEntity>): Int {
        for (row in rows) {
            if (!row.deleted) {
                saveAndLog(row.toDomain(), row.dedupeKey, row.dedupeAt)
                continue
            }
            // A tombstone cannot travel as an UPSERT. SyncPayload has no `deleted`
            // field — deletion is carried by the op, not the payload — so an UPSERT
            // for a deleted row is read by the other phone as "here it is again", and
            // a restore would un-delete on her handset everything you had thrown away
            // on yours.
            val lamport = clock.tick()
            val deviceId = prefs.deviceId()
            txnDao.upsert(row.copy(updatedAtLamport = lamport, updatedByDevice = deviceId))
            prefs.writeLamport(lamport)
            eventDao.append(
                SyncEventEntity(
                    eventId = UUID.randomUUID().toString(),
                    txnId = row.id,
                    op = SyncOp.DELETE.name,
                    lamport = lamport,
                    deviceId = deviceId,
                    ownerUid = row.ownerUid,
                    payloadJson = null,
                    wallClock = System.currentTimeMillis(),
                    pushed = false,
                )
            )
        }
        return rows.size
    }

    /**
     * Rebuilds this device's outbound log from the transactions table.
     *
     * "Re-upload everything" cannot simply un-mark events as unsent, because there may
     * be no events left to un-mark: compaction deletes superseded entries once pushed,
     * and until recently it deleted rather more than that. The transactions table is the
     * source of truth and survives all of it, so the log is regenerated from there.
     *
     * Returns what was minted *and* what the cutoff held back, so the caller can say so.
     */
    suspend fun rebuildOwnLog(): ReuploadTally {
        // Respects the tracking cutoff. Retiring a month is a statement about what the
        // household counts as theirs, not merely about what this screen draws — so a
        // month you have retired is not shipped to the sheet or to the other phone
        // either. Without this the sheet held every row the app had stopped showing.
        val startAt = prefs.trackingStartAtOnce()
        // Counted before the rebuild, and reported by the caller. The rule is deliberate;
        // being quiet about it was not. A button that says "everything" and uploads a
        // fraction has to name the fraction, or the success message is a false one.
        val retired = if (startAt > 0L) txnDao.countBefore(startAt) else 0
        val transactions = txnDao.getBetween(startAt, Long.MAX_VALUE).filterNot { it.deleted }
        // Drop the old log first, or every transaction ends up with two events saying
        // the same thing and the sheet grows a duplicate of its entire history.
        eventDao.deleteAll()
        for (row in transactions) {
            saveAndLog(row.toDomain(), row.dedupeKey, row.dedupeAt)
        }
        return ReuploadTally(queued = transactions.size, retired = retired)
    }

    /**
     * How many stored expenses the tracking cutoff is currently holding back.
     *
     * Read by Settings so the reach of the cutoff is visible *before* a re-upload rather
     * than inferred from the count afterwards. Zero when no cutoff is set.
     */
    fun observeRetiredCount(): Flow<Int> = prefs.trackingStartAt.flatMapLatest { startAt ->
        if (startAt <= 0L) flowOf(0) else txnDao.observeCountBefore(startAt)
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
        /** Shared-rule type for an account the household has named. */
        const val TYPE_ACCOUNT = "account"

        /**
         * A balance somebody typed in, for the banks that never quote one.
         *
         * Shared rather than device-local, for the same reason the account names are:
         * the household has one set of accounts between them, and a figure only one
         * phone knows about is a figure the other phone is missing.
         */
        const val TYPE_BALANCE = "balance"

        /** What a bank insists stays in an account. Zero for a zero-balance account. */
        const val TYPE_MIN_BALANCE = "minbal"

        /**
         * How close the two legs of an own-account transfer must be.
         *
         * Wide enough for a bank to text the second leg late, narrow enough that a real
         * expense and an unrelated credit of the same amount later that day are not
         * quietly cancelled against each other.
         */
        const val SELF_TRANSFER_WINDOW_MS = 30 * 60 * 1000L

        /** Lower-cased and trimmed. Whatever a bank calls the destination account. */
        val ACCOUNT_LABELS = listOf(
            "a/c no", "a/c no.", "ac no", "acct no", "account no", "a/c number", "a/c",
        )

        /** Shown when the bank's message names no payee at all. */
        const val UNKNOWN_PAYEE = "Unknown payee"
        const val MIN_RULE_LENGTH = 3

        /** How far back the category predictor looks. */
        private const val PREDICTION_HISTORY = 400
    }
}
