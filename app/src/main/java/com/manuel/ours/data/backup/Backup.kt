package com.manuel.ours.data.backup

import com.manuel.ours.data.db.BudgetEntity
import com.manuel.ours.data.db.MemberEntity
import com.manuel.ours.data.db.MerchantRuleEntity
import com.manuel.ours.data.db.ReminderEntity
import com.manuel.ours.data.db.SharedRuleEntity
import com.manuel.ours.data.db.TransactionEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The whole history in one file, so a lost phone is an inconvenience rather than an
 * amputation.
 *
 * The app's central warning is that manual entries and hand-made category corrections
 * exist **only** in the phone's database. SMS backfill rebuilds what the banks sent; it
 * cannot rebuild what a person typed or fixed. The sheet is a sync log — it carries
 * events, not a restorable history, and a re-created sheet starts empty. This file is the
 * only thing in the project that answers "the phone is gone, now what".
 *
 * ## A deliberately separate shape from the Room entities
 *
 * These classes duplicate the entity fields rather than serialising the entities
 * directly. That looks like copy-paste and is the point: a rename or a re-typing in
 * `Entities.kt` is a schema migration for the database and would silently be a *format*
 * change for every backup ever written. Keeping them apart means the compiler flags the
 * mapping instead of a two-year-old file failing to restore.
 *
 * ## What is deliberately not here
 *
 * - **`sync_events`** — an append-only log, rebuildable from the transactions by
 *   `rebuildOwnLog()`, and by far the largest table. Restoring stale events would also
 *   replay a Lamport ordering the household has moved past.
 * - **`peer_cursors`** — where sync got to on a phone that no longer exists.
 * - **The invite secret, the sheet URL, and the database key.** The first two are
 *   capability credentials: anyone holding them can read and write the household's
 *   sheet, and a backup is a file that ends up in Drive or an inbox. The third is
 *   Android-Keystore-backed and cannot leave the device meaningfully anyway. A restored
 *   phone re-joins by QR and re-pastes its sheet URL, which are both things a person can
 *   redo and neither of which is data.
 *
 * ## What it does carry, and what that means
 *
 * `rawSms` is included, because it is the one column the sheet never receives and the
 * only way a future parser fix can be applied to old messages. It also means this file
 * contains **original bank messages, with account tails and running balances, in the
 * clear**. It is not encrypted — a passphrase that can be forgotten turns a safety net
 * into a second way to lose everything, and the honest trade is to say plainly what the
 * file holds and let it be stored somewhere private.
 */
@Serializable
data class BackupFile(
    val format: String = BackupCodec.FORMAT,
    val version: Int = BackupCodec.VERSION,
    val createdAt: Long,
    val appVersionName: String = "",
    val appVersionCode: Int = 0,
    /**
     * Who this phone was. Used to re-attribute rows on restore: a new phone mints a new
     * uid, and without this every restored row would belong to a member who is not you.
     */
    val selfUid: String? = null,
    val selfName: String? = null,
    val householdId: String? = null,
    val trackingStartAt: Long = 0L,
    val transactions: List<BackupTxn> = emptyList(),
    val sharedRules: List<BackupRule> = emptyList(),
    val merchantRules: List<BackupMerchantRule> = emptyList(),
    val budgets: List<BackupBudget> = emptyList(),
    val members: List<BackupMember> = emptyList(),
    val reminders: List<BackupReminder> = emptyList(),
)

@Serializable
data class BackupTxn(
    val id: String,
    val amountPaise: Long,
    val type: String,
    val merchant: String,
    val category: String,
    val occurredAt: Long,
    val accountTail: String? = null,
    val refNo: String? = null,
    val bank: String? = null,
    val note: String? = null,
    val splitType: String,
    val source: String,
    val ownerUid: String,
    val ownerName: String,
    val needsReview: Boolean = false,
    val rawSms: String? = null,
    val deleted: Boolean = false,
    val deleteRequestedBy: String? = null,
    val amountEditedAt: Long? = null,
    val counterpartyTail: String? = null,
    val balancePaise: Long? = null,
    val dedupeKey: String,
    val dedupeAt: Long,
    val updatedAtLamport: Long = 0L,
    val updatedByDevice: String = "",
)

@Serializable
data class BackupRule(
    val type: String,
    val ruleKey: String,
    val value: String,
    val updatedAt: Long,
    val deviceId: String = "",
)

@Serializable
data class BackupMerchantRule(
    val pattern: String,
    val category: String,
    val userDefined: Boolean = true,
)

@Serializable
data class BackupBudget(val categoryKey: String, val limitPaise: Long)

@Serializable
data class BackupMember(
    val uid: String,
    val displayName: String,
    val email: String = "",
    val isSelf: Boolean = false,
)

@Serializable
data class BackupReminder(
    val id: String,
    val bank: String? = null,
    val amountPaise: Long? = null,
    val dueAt: Long,
    val text: String,
    val dismissed: Boolean = false,
)

/** What reading a chosen file produced. Every failure names itself. */
sealed interface BackupRead {
    data class Ok(val file: BackupFile) : BackupRead

    /** Not JSON at all, or JSON that is not one of ours. */
    data class Unreadable(val detail: String) : BackupRead

    /**
     * Written by a later version of the app than this one.
     *
     * Refused rather than best-effort parsed: `ignoreUnknownKeys` would happily drop a
     * field a future version depends on and report a clean restore having silently
     * discarded it.
     */
    data class TooNew(val fileVersion: Int, val supported: Int) : BackupRead
}

object BackupCodec {

    const val FORMAT = "ours.backup"
    const val VERSION = 1

    /**
     * `ignoreUnknownKeys` so a file from a *later* v1 that added an optional field still
     * restores. The version gate above is what protects against a genuine format break;
     * this only forgives additions.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /**
     * The header is read before the body, on purpose.
     *
     * Deserialising first and checking `format` afterwards works only when the file *is*
     * one of ours. Point it at any other JSON and it fails on whichever required field
     * happens to be missing, so the person who picked the wrong file is told
     * "Field 'createdAt' is required for type with serial name …" instead of "that is not
     * an Ours backup". Reading two fields off the raw tree first costs nothing and is the
     * difference between a sentence and a stack trace.
     */
    fun decode(text: String): BackupRead {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            // Deliberately not e.message. The parser's own words are "Unexpected JSON
            // token at offset 6: Expected EOF after parsing, but had i instead at path:
            // $", followed by a quotation of the file — which told a person nothing and
            // reprinted the contents of whatever they had picked into the interface. A
            // wrong file is a wrong file.
            return BackupRead.Unreadable("this is not an Ours backup")
        } ?: return BackupRead.Unreadable("this is not an Ours backup")

        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) {
            return BackupRead.Unreadable("this is not an Ours backup")
        }
        val version = (root["version"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        if (version > VERSION) return BackupRead.TooNew(version, VERSION)

        return try {
            BackupRead.Ok(json.decodeFromJsonElement(BackupFile.serializer(), root))
        } catch (e: Exception) {
            // Ours by its header, but damaged — truncated by a failed upload, most
            // likely. Named as damage rather than as the wrong file, and without
            // quoting the file back at the reader.
            BackupRead.Unreadable("the backup is incomplete or damaged")
        }
    }
}

// ---- entity <-> backup ------------------------------------------------------------
// Written out longhand on purpose. See the class comment: this is the seam where a
// schema change must be noticed rather than silently reinterpreted.

fun TransactionEntity.toBackup() = BackupTxn(
    id = id,
    amountPaise = amountPaise,
    type = type,
    merchant = merchant,
    category = category,
    occurredAt = occurredAt,
    accountTail = accountTail,
    refNo = refNo,
    bank = bank,
    note = note,
    splitType = splitType,
    source = source,
    ownerUid = ownerUid,
    ownerName = ownerName,
    needsReview = needsReview,
    rawSms = rawSms,
    deleted = deleted,
    deleteRequestedBy = deleteRequestedBy,
    amountEditedAt = amountEditedAt,
    counterpartyTail = counterpartyTail,
    balancePaise = balancePaise,
    dedupeKey = dedupeKey,
    dedupeAt = dedupeAt,
    updatedAtLamport = updatedAtLamport,
    updatedByDevice = updatedByDevice,
)

fun BackupTxn.toEntity() = TransactionEntity(
    id = id,
    amountPaise = amountPaise,
    type = type,
    merchant = merchant,
    category = category,
    occurredAt = occurredAt,
    accountTail = accountTail,
    refNo = refNo,
    bank = bank,
    note = note,
    splitType = splitType,
    source = source,
    ownerUid = ownerUid,
    ownerName = ownerName,
    needsReview = needsReview,
    rawSms = rawSms,
    deleted = deleted,
    deleteRequestedBy = deleteRequestedBy,
    amountEditedAt = amountEditedAt,
    counterpartyTail = counterpartyTail,
    balancePaise = balancePaise,
    dedupeKey = dedupeKey,
    dedupeAt = dedupeAt,
    updatedAtLamport = updatedAtLamport,
    updatedByDevice = updatedByDevice,
)

fun SharedRuleEntity.toBackup() = BackupRule(type, ruleKey, value, updatedAt, deviceId)

fun BackupRule.toEntity() = SharedRuleEntity(type, ruleKey, value, updatedAt, deviceId)

fun MerchantRuleEntity.toBackup() = BackupMerchantRule(pattern, category, userDefined)

fun BudgetEntity.toBackup() = BackupBudget(categoryKey, limitPaise)

fun BackupBudget.toEntity() = BudgetEntity(categoryKey, limitPaise)

fun MemberEntity.toBackup() = BackupMember(uid, displayName, email, isSelf)

fun BackupMember.toEntity() = MemberEntity(uid, displayName, email, isSelf)

fun ReminderEntity.toBackup() = BackupReminder(id, bank, amountPaise, dueAt, text, dismissed)

fun BackupReminder.toEntity() = ReminderEntity(id, bank, amountPaise, dueAt, text, dismissed)
