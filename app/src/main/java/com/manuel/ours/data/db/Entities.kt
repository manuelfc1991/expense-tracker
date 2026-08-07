package com.manuel.ours.data.db

import androidx.room.ColumnInfo
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
    /**
     * The bank's own identifier for the message this row came from ("Msg Id 2644123773").
     *
     * Identity, not reference. Kerala Gramin sends two SMS for one debit — one with a UPI
     * reference and one without — and the only thing they share is this number. Dedup
     * could not see it: the refs could not match when only one message had one, and the
     * pair landed three minutes and change apart, just outside the window. ₹8,955.79 of
     * one month was counted twice as a result.
     *
     * Never shown. `refNo` is what a person quotes at the bank; this is bookkeeping.
     */
    val bankMessageId: String? = null,
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
    /**
     * On a **credit**: the purchase this refund cancels, or null if it is ordinary income.
     *
     * Every credit that is not a maturing investment becomes Income, so a ₹2,000 return left the
     * ledger with a ₹2,000 debit *and* a ₹2,000 credit: net worth right, spending overstated by
     * ₹2,000, and the budget charged for a purchase that was undone.
     *
     * Never inferred. Two ₹2,000 movements in a month are far more often two real payments than
     * a purchase and its refund, and this household has already been bitten by a matcher that
     * was too eager — two ₹10,000 movements a minute apart, an FD maturing and rent paid to a
     * person, both real.
     */
    val refundsTxnId: String? = null,
    /**
     * On a **debit**: how much of it has been refunded. Zero for almost every row.
     *
     * Partial is the common case for a multi-item order, so this is an amount rather than a flag —
     * the purchase keeps whatever the refund does not cancel.
     */
    // The SQL default is declared, not just the Kotlin one.
    //
    // A Kotlin default satisfies the constructor but is invisible to Room's schema, so the
    // exported schema would record no default while MIGRATION_8_9 adds `DEFAULT 0`. Room compares
    // the two at open time, and a mismatch there is a crash on launch against the only copy of
    // this household's ledger.
    @ColumnInfo(defaultValue = "0")
    val refundedPaise: Long = 0,
    /** The bank's own closing balance for [accountTail], when the message carried one. */
    val balancePaise: Long? = null,
    /**
     * When this row was deleted, or null if it is live — **and also null for every row
     * deleted before 5.17**, which is the point.
     *
     * Trash is a 30-day window over this column, and the ledger already held 446
     * tombstones on the first real phone: dedupe repairs and bulk tidy-ups soft-delete,
     * so most of them were never a person choosing to throw something away. Backfilling
     * a timestamp onto those would open Trash on 446 entries nobody deleted. A null is
     * read as "older than the window", so Trash starts empty and fills only with
     * deletions somebody actually made.
     */
    val deletedAt: Long? = null,
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
    deletedAt = deletedAt,
    deleteRequestedBy = deleteRequestedBy,
    amountEditedAt = amountEditedAt,
    counterpartyTail = counterpartyTail,
    balancePaise = balancePaise,
    bankMessageId = bankMessageId,
    // Both halves of a refund. Omitted here originally, which silently erased the
    // link on every write — `linkRefund` does `entity.copy(...).toDomain()`, so the
    // column it had just set was dropped on the way back out and the whole refund
    // feature never persisted. RefundTest builds domain objects directly and never
    // crosses this mapper, which is why it stayed green.
    refundsTxnId = refundsTxnId,
    refundedPaise = refundedPaise,
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
    bankMessageId = bankMessageId,
    refundsTxnId = refundsTxnId,
    refundedPaise = refundedPaise,
    bank = bank,
    note = note,
    splitType = splitType.name,
    source = source.name,
    ownerUid = ownerUid,
    ownerName = ownerName,
    needsReview = needsReview,
    rawSms = rawSms,
    deleted = deleted,
    deletedAt = deletedAt,
    deleteRequestedBy = deleteRequestedBy,
    amountEditedAt = amountEditedAt,
    counterpartyTail = counterpartyTail,
    balancePaise = balancePaise,
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
    /**
     * JSON of the transaction, **including rawSms**.
     *
     * The redaction is not here — `SheetTransport.push` strips it on the way out, so the
     * sheet never sees message text while the local log and the Bluetooth transport do.
     * This comment used to claim the payload was written without it, which would send
     * anyone "fixing" the code in exactly the wrong direction.
     */
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

/**
 * A sender that writes payment-shaped messages and that nobody has vouched for.
 *
 * One row per header, not per message: 99 unrecognised headers on the real phone carried
 * 1,386 messages, and asking about each message is 1,386 decisions where asking about each
 * sender is at most 99 — in practice the handful that mention an amount at all.
 *
 * **Never synced.** It holds message text, which is treated exactly as `rawSms` is: it stays
 * on the phone that received it. What travels is the *answer*, as a `sender` shared rule.
 */
@Entity(tableName = "pending_senders")
data class PendingSenderEntity(
    @PrimaryKey val header: String,
    val messageCount: Int,
    val firstAt: Long,
    val lastAt: Long,
    /** The most recent message, shown so a person can recognise what this is. */
    val sampleBody: String,
    /** The most recent amount, for the figure on the row. Null when none could be read. */
    val lastAmountPaise: Long?,
)
