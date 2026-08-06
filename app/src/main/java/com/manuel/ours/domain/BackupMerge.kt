package com.manuel.ours.domain

import com.manuel.ours.data.backup.BackupTxn
import com.manuel.ours.data.backup.toEntity
import com.manuel.ours.data.db.TransactionEntity

/**
 * What a restore does to the rows already on the phone.
 *
 * The governing rule is that **a restore only ever adds**. It never clears a table, never
 * resurrects something deleted here, and never overwrites a row it cannot positively
 * identify as the same transaction. A backup is opened by someone who has already lost
 * data; the one unacceptable outcome is that opening it loses more.
 *
 * ## Why matching by id alone is not enough
 *
 * The realistic restore is onto a phone that has *already re-read the SMS inbox*. Those
 * rows are the same transactions with **different ids** — an id is a fresh UUID minted at
 * parse time. Matching on id alone would therefore duplicate the entire history, and the
 * hand-made corrections in the backup would attach to nothing.
 *
 * `dedupeKey` is what survives a re-parse: (amount, rounded time bucket, account tail).
 * It is already the column the ingest path trusts to recognise a message it has seen, so
 * it is the right identity here too.
 *
 * ## The three outcomes
 *
 *  - **restored** — nothing on this phone matches. Inserted as it was.
 *  - **reconciled** — the same transaction is here, from a re-parse, but the backup
 *    carries a person's corrections it does not have. The corrections are applied to the
 *    local row; the local id, dedupe columns and parser-derived fields stay.
 *  - **untouched** — already present and already agrees. This is the common case on a
 *    second restore, and it is why running one twice is safe.
 *
 * ## Re-attribution
 *
 * A replacement phone mints a new uid, so every row in the backup would belong to a
 * member who is not the person holding it — their own history would show up as somebody
 * else's share. Rows owned by the backup's own uid are therefore re-pointed at this
 * phone's uid. Rows owned by *anyone else* are left alone: the partner's transactions
 * stay the partner's.
 */
object BackupMerge {

    data class Plan(
        val inserts: List<TransactionEntity> = emptyList(),
        val updates: List<TransactionEntity> = emptyList(),
        val untouched: Int = 0,
        val reattributed: Int = 0,
    ) {
        val restored: Int get() = inserts.size
        val reconciled: Int get() = updates.size
        val touched: Int get() = restored + reconciled

        /**
         * One line, in the same voice as the rest of the app: say what happened, and do
         * not let "nothing to do" look like "it failed".
         */
        fun summaryLine(): String {
            if (touched == 0 && untouched == 0) return "That backup holds no expenses"
            if (touched == 0) {
                return "Everything in that backup was already here — " +
                    "${count(untouched, "expense")}, nothing to add"
            }
            val parts = buildList {
                if (restored > 0) add("restored ${count(restored, "expense")}")
                if (reconciled > 0) add("put back corrections on ${count(reconciled, "more")}")
            }
            val head = parts.joinToString(" and ").replaceFirstChar { it.uppercase() }
            return if (untouched > 0) "$head. ${count(untouched, "other")} already matched." else "$head."
        }

        private fun count(n: Int, noun: String) = when {
            n == 1 && noun == "expense" -> "1 expense"
            noun == "expense" -> "$n expenses"
            n == 1 && noun == "more" -> "1 more"
            noun == "more" -> "$n more"
            n == 1 -> "1 other"
            else -> "$n others"
        }
    }

    /**
     * @param local every row already on this phone, **including deleted ones** — a
     *   tombstone here must be able to match, or a restore silently un-deletes.
     */
    fun plan(
        backup: List<BackupTxn>,
        local: List<TransactionEntity>,
        backupSelfUid: String?,
        localSelfUid: String?,
        localSelfName: String?,
    ): Plan {
        val byId = local.associateBy { it.id }
        // Deleted rows lose to live ones when two share a key, so a correction lands on
        // the row still being counted. Sorted deleted-first because associateBy keeps the
        // *last* value for a duplicate key — sorting the intuitive way round hands every
        // contested key to the tombstone.
        val byDedupe = local.sortedByDescending { it.deleted }.associateBy { it.dedupeKey }

        val inserts = mutableListOf<TransactionEntity>()
        val updates = mutableListOf<TransactionEntity>()
        var untouched = 0
        var reattributed = 0

        val remap = backupSelfUid != null &&
            localSelfUid != null &&
            backupSelfUid != localSelfUid

        for (row in backup) {
            val owned = remap && row.ownerUid == backupSelfUid
            val incoming = if (owned) {
                row.copy(ownerUid = localSelfUid!!, ownerName = localSelfName ?: row.ownerName)
            } else {
                row
            }
            if (owned) reattributed++

            val existing = byId[row.id] ?: byDedupe[row.dedupeKey]
            if (existing == null) {
                inserts += incoming.toEntity()
                continue
            }
            val merged = reconcile(existing, incoming)
            if (merged == null) untouched++ else updates += merged
        }
        return Plan(inserts, updates, untouched, reattributed)
    }

    /**
     * The local row with the backup's *human* fields applied, or null if nothing differs.
     *
     * Only the fields a person can change travel. Everything the parser derives — the
     * account tail, the bank, the reference, the balance the message quoted — stays as
     * this phone read it, because a later parser is more likely to be right than an
     * older one.
     */
    private fun reconcile(local: TransactionEntity, backup: BackupTxn): TransactionEntity? {
        // A deletion made here is newer knowledge than the backup. Never undo it.
        val deleted = local.deleted || backup.deleted
        // A hand-edited amount is the only reason to prefer the backup's figure over the
        // one this phone read off the message.
        val handEdited = backup.amountEditedAt != null
        val amount = if (handEdited) backup.amountPaise else local.amountPaise
        val editedAt = backup.amountEditedAt ?: local.amountEditedAt

        val merged = local.copy(
            merchant = backup.merchant,
            category = backup.category,
            note = backup.note ?: local.note,
            splitType = backup.splitType,
            // Anything reviewed on either phone stays reviewed; a restore should not
            // re-open a queue somebody has already worked through.
            needsReview = local.needsReview && backup.needsReview,
            deleted = deleted,
            deleteRequestedBy = backup.deleteRequestedBy ?: local.deleteRequestedBy,
            amountPaise = amount,
            amountEditedAt = editedAt,
        )
        return if (merged == local) null else merged
    }
}
