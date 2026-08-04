package com.manuel.ours.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType

@Entity(
    tableName = "transactions",
    indices = [
        Index("occurredAt"),
        Index("ownerUid"),
        Index("category"),
        Index("dedupeKey"),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val amountPaise: Long,
    val type: String,
    val merchant: String,
    val category: String,
    val occurredAt: Long,
    val accountTail: String?,
    val refNo: String?,
    val bank: String?,
    val note: String?,
    val splitType: String,
    val source: String,
    val ownerUid: String,
    val ownerName: String,
    val needsReview: Boolean,
    /** Raw SMS body. Kept locally for debugging and re-parsing; NEVER synced. */
    val rawSms: String?,
    val deleted: Boolean,
    /**
     * Uid of a member who asked for this row to be removed but is not the household
     * owner. The row stays visible and countable until the owner decides — a pending
     * request must not quietly change anybody's totals.
     */
    val deleteRequestedBy: String? = null,
    /**
     * When the amount was last changed by hand, or null if it is still the bank's.
     *
     * Kept because an edited figure no longer reconciles against the statement it came
     * from, and a row that quietly disagrees with the bank is worse than one that says
     * why. A timestamp rather than a flag, so a future reader can tell whether the edit
     * predates the discrepancy they are chasing.
     */
    val amountEditedAt: Long? = null,
    /** Last digits of the account paid, when the bank named one. See SmsParser. */
    val counterpartyTail: String? = null,
    /** (amount, rounded time bucket, account tail) — collapses the duplicate bank + UPI-app SMS. */
    val dedupeKey: String,
    /**
     * Timestamp used for duplicate detection, which is NOT always [occurredAt].
     *
     * When a bank names a day but no clock time, occurredAt is midnight — identical
     * for every transaction that day. This column stores the resolvable time (the SMS
     * delivery time in that case) so a rescan can recognise a message it has already
     * seen. Comparing a stored midnight against an incoming real time never matches,
     * and every rescan silently duplicates the entire history.
     */
    val dedupeAt: Long,
    val updatedAtLamport: Long,
    val updatedByDevice: String,
)

fun TransactionEntity.toDomain() = Transaction(
    id = id,
    amountPaise = amountPaise,
    type = TxnType.valueOf(type),
    merchant = merchant,
    category = Category.fromNameOrOther(category),
    occurredAt = occurredAt,
    accountTail = accountTail,
    refNo = refNo,
    bank = bank,
    note = note,
    splitType = SplitType.valueOf(splitType),
    source = TxnSource.valueOf(source),
    ownerUid = ownerUid,
    ownerName = ownerName,
    needsReview = needsReview,
    rawSms = rawSms,
    deleted = deleted,
    deleteRequestedBy = deleteRequestedBy,
    amountEditedAt = amountEditedAt,
    counterpartyTail = counterpartyTail,
)

fun Transaction.toEntity(
    dedupeKey: String,
    lamport: Long,
    deviceId: String,
    dedupeAt: Long = occurredAt,
) = TransactionEntity(
    id = id,
    amountPaise = amountPaise,
    type = type.name,
    merchant = merchant,
    category = category.name,
    occurredAt = occurredAt,
    accountTail = accountTail,
    refNo = refNo,
    bank = bank,
    note = note,
    splitType = splitType.name,
    source = source.name,
    ownerUid = ownerUid,
    ownerName = ownerName,
    needsReview = needsReview,
    rawSms = rawSms,
    deleted = deleted,
    deleteRequestedBy = deleteRequestedBy,
    amountEditedAt = amountEditedAt,
    counterpartyTail = counterpartyTail,
    dedupeKey = dedupeKey,
    dedupeAt = dedupeAt,
    updatedAtLamport = lamport,
    updatedByDevice = deviceId,
)

/**
 * This device's own outbound event log. Rows are appended, never mutated — that is
 * what makes sync conflict-free. Cleared only by compaction.
 */
@Entity(tableName = "sync_events", indices = [Index("lamport")])
data class SyncEventEntity(
    @PrimaryKey val eventId: String,
    val txnId: String,
    val op: String,
    val lamport: Long,
    val deviceId: String,
    val ownerUid: String,
    /** JSON of the transaction, minus rawSms. Null for DELETE tombstones. */
    val payloadJson: String?,
    val wallClock: Long,
    val pushed: Boolean,
)

/** Highest lamport we have already merged from each peer device, so we can skip re-reading. */
@Entity(tableName = "peer_cursors")
data class PeerCursorEntity(
    @PrimaryKey val deviceId: String,
    val lastMergedLamport: Long,
    val lastSeenAt: Long,
)

@Entity(tableName = "merchant_rules", indices = [Index(value = ["pattern"], unique = true)])
data class MerchantRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Lowercase substring matched against the merchant string. */
    val pattern: String,
    val category: String,
    /** User corrections outrank the seeded defaults. */
    val userDefined: Boolean,
)

/**
 * A rule the phones teach each other through the sheet.
 *
 * Two kinds, keyed by [type]:
 *  - `sender`  : a TRAI header the built-in table does not know, e.g. KGBANK ->
 *                "Kerala Gramin Bank". An unrecognised header is dropped before
 *                parsing, which is how one missing line once discarded 466 messages.
 *  - `merchant`: a merchant substring to a category, so a correction made on one
 *                phone stops being made again on the other.
 *
 * Deliberately *not* regexes. The parser's patterns are code and stay code; what
 * travels here is the data a person could reasonably type into a spreadsheet.
 */
@Entity(tableName = "shared_rules", primaryKeys = ["type", "ruleKey"])
data class SharedRuleEntity(
    val type: String,
    val ruleKey: String,
    val value: String,
    /** Last-write-wins. A phone offline for a week cannot undo a newer correction. */
    val updatedAt: Long,
    val deviceId: String,
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    /** Category name, or "__OVERALL__" for the monthly cap. */
    @PrimaryKey val categoryKey: String,
    val limitPaise: Long,
)

@Entity(tableName = "members")
data class MemberEntity(
    @PrimaryKey val uid: String,
    val displayName: String,
    val email: String,
    val isSelf: Boolean,
)

/** Bill-due reminders parsed out of SMS. These are upcoming, not spent — kept apart. */
@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val bank: String?,
    val amountPaise: Long?,
    val dueAt: Long,
    val text: String,
    val dismissed: Boolean,
)
