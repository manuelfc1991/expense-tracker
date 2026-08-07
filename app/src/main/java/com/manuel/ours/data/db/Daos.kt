package com.manuel.ours.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Upsert
    suspend fun upsert(txn: TransactionEntity)

    @Upsert
    suspend fun upsertAll(txns: List<TransactionEntity>)

    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY occurredAt DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    /**
     * Credits still carrying the placeholder payee. Narrow on purpose: the repair that
     * uses this ran as a full table scan on every launch, decrypting the whole history
     * to find, almost always, nothing.
     */
    @Query("SELECT * FROM transactions WHERE type = 'CREDIT' AND merchant = :placeholder AND bank IS NOT NULL AND deleted = 0")
    suspend fun creditsWithPlaceholderPayee(placeholder: String): List<TransactionEntity>

    /**
     * Same amount, same bank, same account, on the same day — candidates for the
     * twelve-hour twins the AM/PM fix created.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted = 0 AND amountPaise = :amountPaise
          AND IFNULL(bank,'') = IFNULL(:bank,'')
          AND IFNULL(accountTail,'') = IFNULL(:tail,'')
        """
    )
    suspend fun sameAmountAndAccount(
        amountPaise: Long, bank: String?, tail: String?,
    ): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE deleted = 0")
    suspend fun allLive(): List<TransactionEntity>

    /**
     * How many live rows sit before the tracking cutoff — retired, and never synced.
     *
     * Counted in SQL rather than by loading and filtering: this is asked on the Settings
     * screen and again on every re-upload, and the history it walks is the part of the
     * table nothing else reads.
     */
    @Query("SELECT COUNT(*) FROM transactions WHERE deleted = 0 AND occurredAt < :before")
    suspend fun countBefore(before: Long): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE deleted = 0 AND occurredAt < :before")
    fun observeCountBefore(before: Long): Flow<Int>

    /** Rows whose "merchant" is really an account label the parser mistook for a payee. */
    @Query("SELECT * FROM transactions WHERE deleted = 0 AND LOWER(TRIM(merchant)) IN (:labels)")
    suspend fun withMerchantIn(labels: List<String>): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted = 0 AND occurredAt >= :from AND occurredAt < :to
        ORDER BY occurredAt DESC
        """
    )
    fun observeBetween(from: Long, to: Long): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted = 0 AND occurredAt >= :from AND occurredAt < :to
        ORDER BY occurredAt DESC
        """
    )
    suspend fun getBetween(from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    /** Includes soft-deleted rows, which [getById] deliberately does not. */
    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getByIdIncludingDeleted(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observeById(id: String): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions WHERE dedupeKey = :key AND deleted = 0 LIMIT 1")
    suspend fun findByDedupeKey(key: String): TransactionEntity?

    /**
     * Every stored row that could be the same payment, found by value rather than by
     * whichever key shape happened to be used when it was written.
     *
     * The bucket key encodes a UPI ref when one exists and the amount and minute when
     * it does not, so two messages about one payment — the bank naming no ref, the UPI
     * app naming one — were stored under different keys and neither lookup could ever
     * see the other.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted = 0 AND amountPaise = :amountPaise
          AND dedupeAt BETWEEN :fromAt AND :toAt
        """
    )
    suspend fun findNearby(amountPaise: Long, fromAt: Long, toAt: Long): List<TransactionEntity>

    /** A UPI reference is globally unique, so it matches however far apart the two land. */
    @Query("SELECT * FROM transactions WHERE deleted = 0 AND refNo = :refNo LIMIT 1")
    suspend fun findByRef(refNo: String): TransactionEntity?

    /**
     * The same three lookups, **including rows that were deleted**.
     *
     * Dedup that only sees live rows cannot see a deletion, so a rescan re-imported every
     * message whose row somebody had removed — the tombstone was invisible and the message
     * looked new. Six rows deleted on 6 August came back on the next rescan and put ₹50,955
     * of duplicates into a month that had just been cleaned up.
     *
     * A deleted row is still proof the message was seen. What it means is *not* "import
     * this again", it is "we have met this one and the household said no".
     */
    @Query("SELECT * FROM transactions WHERE refNo = :refNo LIMIT 1")
    suspend fun findByRefEvenIfDeleted(refNo: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE bankMessageId = :id LIMIT 1")
    suspend fun findByMessageIdEvenIfDeleted(id: String): TransactionEntity?

    @Query(
        """
        SELECT * FROM transactions
        WHERE amountPaise = :amountPaise AND dedupeAt BETWEEN :fromAt AND :toAt
        """
    )
    suspend fun findNearbyEvenIfDeleted(
        amountPaise: Long, fromAt: Long, toAt: Long,
    ): List<TransactionEntity>

    /**
     * The bank's own message id, which is the only thing tying Kerala Gramin's two
     * SMS for one debit together. Probed directly because the pair falls outside the
     * time window `findNearby` searches.
     */
    @Query("SELECT * FROM transactions WHERE deleted = 0 AND bankMessageId = :id LIMIT 1")
    suspend fun findByMessageId(id: String): TransactionEntity?

    /**
     * Rows carrying this person's name under some *other* id.
     *
     * A reinstall mints a fresh uid, and rows synced back from the sheet keep the old
     * one. The household member list is derived from distinct owner ids, so the same
     * human turns up twice — on the first real phone, one leftover row was enough to
     * put a second "Manuel" in the filter chips.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted = 0 AND ownerUid != :selfUid
          AND LOWER(TRIM(ownerName)) = LOWER(TRIM(:selfName))
        """
    )
    suspend fun rowsOwnedByAlias(selfUid: String, selfName: String): List<TransactionEntity>

    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted = 0 AND deleteRequestedBy IS NOT NULL
        ORDER BY occurredAt DESC
        """
    )
    fun observeDeleteRequests(): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE deleted = 0 AND deleteRequestedBy IS NOT NULL")
    fun observeDeleteRequestCount(): Flow<Int>

    /**
     * Both of these take the tracking-start cutoff, and must.
     *
     * Without it the badge counted rows every list on the app deliberately hides: this
     * household tracks from 1 August, four `needsReview` rows sit before it, and the
     * Activity tab therefore advertised four pieces of work that led to an empty Sort
     * screen and an "Untagged 0" filter. A count you cannot act on is worse than none —
     * it sends someone looking for work that is not there.
     */
    @Query("SELECT COUNT(*) FROM transactions WHERE deleted = 0 AND needsReview = 1 AND occurredAt >= :since")
    fun observeNeedsReviewCount(since: Long): Flow<Int>

    @Query("SELECT * FROM transactions WHERE deleted = 0 AND needsReview = 1 AND occurredAt >= :since ORDER BY occurredAt DESC")
    fun observeNeedsReview(since: Long): Flow<List<TransactionEntity>>

    @Query(
        """
        UPDATE transactions
        SET deleted = 1, deletedAt = :at, updatedAtLamport = :lamport, updatedByDevice = :device
        WHERE id = :id
        """
    )
    suspend fun softDelete(id: String, lamport: Long, device: String, at: Long)

    /**
     * What Trash shows: deleted within the window, newest first.
     *
     * `deletedAt IS NOT NULL` is doing real work — it excludes every tombstone that
     * predates the column, which on the first real phone was 446 rows that dedupe
     * repairs removed rather than a person.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE deleted = 1 AND deletedAt IS NOT NULL AND deletedAt >= :since
        ORDER BY deletedAt DESC
        """
    )
    fun observeTrash(since: Long): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE deleted = 1 AND deletedAt IS NOT NULL AND deletedAt >= :since")
    fun observeTrashCount(since: Long): Flow<Int>

    @Query(
        """
        UPDATE transactions
        SET category = :category, needsReview = 0, updatedAtLamport = :lamport, updatedByDevice = :device
        WHERE id = :id
        """
    )
    suspend fun setCategory(id: String, category: String, lamport: Long, device: String)

    @Query(
        """
        SELECT * FROM transactions WHERE deleted = 0
        ORDER BY occurredAt DESC LIMIT :limit
        """
    )
    suspend fun recent(limit: Int): List<TransactionEntity>

    /**
     * Re-homes transactions imported before the user had an identity.
     *
     * The backfill can start before onboarding finishes, and those rows get the
     * placeholder owner. Once a real uid exists they must be adopted, or every one of
     * them looks like it belongs to the *other* household member — which is why the
     * home screen showed "Me" twice in the Both/Me/Partner toggle.
     */
    @Query("UPDATE transactions SET ownerUid = :uid, ownerName = :name WHERE ownerUid = :placeholder")
    suspend fun adoptOrphans(uid: String, name: String, placeholder: String = "local"): Int

    @Query("SELECT COUNT(*) FROM transactions WHERE ownerUid = :placeholder")
    suspend fun orphanCount(placeholder: String = "local"): Int

    @Query("SELECT MAX(occurredAt) FROM transactions WHERE source = 'SMS'")
    suspend fun latestSmsTimestamp(): Long?

    @Query("SELECT * FROM transactions WHERE deleted = 0")
    suspend fun observeAllOnce(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    /**
     * Everything, tombstones included — for backup and restore only.
     *
     * Every other read here filters `deleted = 0`, which is right for anything that
     * displays or totals. A backup that dropped tombstones would resurrect deleted
     * entries the next time it was restored, and a restore that could not see local
     * tombstones would re-insert what this phone had already thrown away.
     */
    @Query("SELECT * FROM transactions")
    suspend fun allIncludingDeleted(): List<TransactionEntity>

    @Query("DELETE FROM transactions")
    suspend fun clear()
}

@Dao
interface SyncEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun append(event: SyncEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun appendAll(events: List<SyncEventEntity>)

    @Query("SELECT * FROM sync_events ORDER BY lamport ASC")
    suspend fun all(): List<SyncEventEntity>

    @Query("SELECT * FROM sync_events WHERE pushed = 0 ORDER BY lamport ASC")
    suspend fun unpushed(): List<SyncEventEntity>

    @Query("SELECT COUNT(*) FROM sync_events WHERE pushed = 0")
    fun observeUnpushedCount(): Flow<Int>

    @Query("UPDATE sync_events SET pushed = 1 WHERE eventId IN (:ids)")
    suspend fun markPushed(ids: List<String>)

    /**
     * Queues every event for upload again.
     *
     * Used when the sheet changes: a new spreadsheet holds none of this device's
     * history, and "pushed" only ever meant "pushed to the sheet we were using before".
     * Transports are required to be idempotent, so a repeat push is safe.
     */
    @Query("UPDATE sync_events SET pushed = 0")
    suspend fun markAllUnpushed()

    @Query("SELECT COALESCE(MAX(lamport), 0) FROM sync_events")
    suspend fun maxLamport(): Long

    @Query("SELECT COUNT(*) FROM sync_events")
    suspend fun count(): Int

    /**
     * Deletes exactly the events named, and only if they have been pushed.
     *
     * Replaces a watermark delete — `lamport <= :upTo AND pushed = 1` — which took the
     * *highest lamport among superseded events* and removed everything below it. Any
     * event compaction had decided to keep but which happened to sit under that
     * watermark went too, so a batch re-categorisation could wipe most of the log. The
     * transactions survived, being a separate table, but the device lost its ability to
     * transmit its own history to a new sheet or a new peer.
     */
    @Query("DELETE FROM sync_events WHERE eventId IN (:ids) AND pushed = 1")
    suspend fun deletePushedByIds(ids: List<String>)

    @Query("DELETE FROM sync_events")
    suspend fun deleteAll()
}

@Dao
interface PeerCursorDao {
    @Upsert
    suspend fun upsert(cursor: PeerCursorEntity)

    @Query("SELECT * FROM peer_cursors")
    suspend fun all(): List<PeerCursorEntity>

    @Query("SELECT lastMergedLamport FROM peer_cursors WHERE deviceId = :deviceId")
    suspend fun cursorFor(deviceId: String): Long?
}

@Dao
interface MerchantRuleDao {
    @Upsert
    suspend fun upsert(rule: MerchantRuleEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rules: List<MerchantRuleEntity>)

    @Query("SELECT * FROM merchant_rules ORDER BY userDefined DESC, LENGTH(pattern) DESC")
    suspend fun all(): List<MerchantRuleEntity>

    @Query("SELECT * FROM merchant_rules ORDER BY userDefined DESC, LENGTH(pattern) DESC")
    fun observeAll(): Flow<List<MerchantRuleEntity>>

    @Query("DELETE FROM merchant_rules WHERE id = :id")
    suspend fun delete(id: Long)

    /** For an emptied `merchant` shared rule, which is this store's tombstone. */
    @Query("DELETE FROM merchant_rules WHERE pattern = :pattern")
    suspend fun deleteByPattern(pattern: String)

    @Query("SELECT * FROM merchant_rules WHERE id = :id")
    suspend fun getById(id: Long): MerchantRuleEntity?

    @Query("SELECT COUNT(*) FROM merchant_rules")
    suspend fun count(): Int

    /** The user's own corrections — the only ones worth teaching the other phone. */
    @Query("SELECT * FROM merchant_rules WHERE userDefined = 1")
    suspend fun userDefined(): List<MerchantRuleEntity>
}

@Dao
interface BudgetDao {
    @Upsert
    suspend fun upsert(budget: BudgetEntity)

    @Query("SELECT * FROM budgets")
    fun observeAll(): Flow<List<BudgetEntity>>

    @Query("SELECT * FROM budgets")
    suspend fun all(): List<BudgetEntity>

    @Query("SELECT limitPaise FROM budgets WHERE categoryKey = :key")
    suspend fun limitFor(key: String): Long?

    @Query("DELETE FROM budgets WHERE categoryKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface MemberDao {
    @Upsert
    suspend fun upsert(member: MemberEntity)

    @Upsert
    suspend fun upsertAll(members: List<MemberEntity>)

    @Query("SELECT * FROM members")
    fun observeAll(): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members")
    suspend fun all(): List<MemberEntity>

    @Query("SELECT * FROM members WHERE isSelf = 1 LIMIT 1")
    suspend fun self(): MemberEntity?

    @Query("DELETE FROM members WHERE uid = :uid")
    suspend fun delete(uid: String)
}

@Dao
interface ReminderDao {
    @Upsert
    suspend fun upsert(reminder: ReminderEntity)

    @Query("SELECT * FROM reminders WHERE dismissed = 0 AND dueAt >= :now ORDER BY dueAt ASC")
    fun observeUpcoming(now: Long): Flow<List<ReminderEntity>>

    @Query("UPDATE reminders SET dismissed = 1 WHERE id = :id")
    suspend fun dismiss(id: String)

    /** Dismissed ones too — a backup that forgot them would re-raise old reminders. */
    @Query("SELECT * FROM reminders")
    suspend fun all(): List<ReminderEntity>

    @Upsert
    suspend fun upsertAll(reminders: List<ReminderEntity>)
}

@Dao
interface SharedRuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rules: List<SharedRuleEntity>)

    @Query("SELECT * FROM shared_rules")
    suspend fun all(): List<SharedRuleEntity>

    @Query("SELECT * FROM shared_rules WHERE type = :type")
    suspend fun ofType(type: String): List<SharedRuleEntity>

    @Query("SELECT * FROM shared_rules WHERE type = :type")
    fun observeOfType(type: String): kotlinx.coroutines.flow.Flow<List<SharedRuleEntity>>

    @Query("SELECT * FROM shared_rules WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<SharedRuleEntity>
}

@Dao
interface PendingSenderDao {

    @Query("SELECT * FROM pending_senders ORDER BY lastAt DESC")
    fun observeAll(): Flow<List<PendingSenderEntity>>

    @Query("SELECT COUNT(*) FROM pending_senders")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_senders WHERE header = :header")
    suspend fun find(header: String): PendingSenderEntity?

    @Upsert
    suspend fun upsert(row: PendingSenderEntity)

    @Query("DELETE FROM pending_senders WHERE header = :header")
    suspend fun delete(header: String)

    @Query("DELETE FROM pending_senders")
    suspend fun clear()
}
