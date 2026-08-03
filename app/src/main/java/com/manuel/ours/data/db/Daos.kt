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

    @Query("SELECT COUNT(*) FROM transactions WHERE deleted = 0 AND needsReview = 1")
    fun observeNeedsReviewCount(): Flow<Int>

    @Query("SELECT * FROM transactions WHERE deleted = 0 AND needsReview = 1 ORDER BY occurredAt DESC")
    fun observeNeedsReview(): Flow<List<TransactionEntity>>

    @Query("UPDATE transactions SET deleted = 1, updatedAtLamport = :lamport, updatedByDevice = :device WHERE id = :id")
    suspend fun softDelete(id: String, lamport: Long, device: String)

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

    @Query("SELECT COUNT(*) FROM merchant_rules")
    suspend fun count(): Int
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
}
