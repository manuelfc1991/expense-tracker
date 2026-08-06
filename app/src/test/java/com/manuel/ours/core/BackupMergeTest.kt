package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.backup.BackupTxn
import com.manuel.ours.data.db.TransactionEntity
import com.manuel.ours.domain.BackupMerge
import org.junit.Test

/**
 * What opening a backup does to the rows already on the phone.
 *
 * The case worth caring about is not the empty phone — that one is a copy. It is the
 * phone that has already re-read the SMS inbox, so the same transactions are present
 * under **different ids**, carrying none of the hand-made corrections the backup exists
 * to protect. Get that wrong and a restore either duplicates the whole history or
 * silently applies nothing.
 */
class BackupMergeTest {

    private fun local(
        id: String,
        dedupeKey: String,
        merchant: String = "SWIGGY",
        category: String = "Food",
        amountPaise: Long = 45075,
        deleted: Boolean = false,
        needsReview: Boolean = false,
        note: String? = null,
        ownerUid: String = "me",
    ) = TransactionEntity(
        id = id,
        amountPaise = amountPaise,
        type = "DEBIT",
        merchant = merchant,
        category = category,
        occurredAt = 1_000L,
        accountTail = "3062",
        refNo = null,
        bank = "KGBANK",
        note = note,
        splitType = "SHARED",
        source = "SMS",
        ownerUid = ownerUid,
        ownerName = "Manuel",
        needsReview = needsReview,
        rawSms = "raw",
        deleted = deleted,
        dedupeKey = dedupeKey,
        dedupeAt = 1_000L,
        updatedAtLamport = 5L,
        updatedByDevice = "phone-a",
    )

    private fun backup(
        id: String,
        dedupeKey: String,
        merchant: String = "SWIGGY",
        category: String = "Food",
        amountPaise: Long = 45075,
        amountEditedAt: Long? = null,
        deleted: Boolean = false,
        needsReview: Boolean = false,
        note: String? = null,
        ownerUid: String = "me",
    ) = BackupTxn(
        id = id,
        amountPaise = amountPaise,
        type = "DEBIT",
        merchant = merchant,
        category = category,
        occurredAt = 1_000L,
        accountTail = "3062",
        splitType = "SHARED",
        source = "SMS",
        ownerUid = ownerUid,
        ownerName = "Manuel",
        needsReview = needsReview,
        note = note,
        deleted = deleted,
        amountEditedAt = amountEditedAt,
        dedupeKey = dedupeKey,
        dedupeAt = 1_000L,
    )

    @Test
    fun `an empty phone takes everything`() {
        val plan = BackupMerge.plan(
            backup = listOf(backup("a", "k1"), backup("b", "k2")),
            local = emptyList(),
            backupSelfUid = null, localSelfUid = null, localSelfName = null,
        )

        assertThat(plan.restored).isEqualTo(2)
        assertThat(plan.reconciled).isEqualTo(0)
        assertThat(plan.untouched).isEqualTo(0)
    }

    @Test
    fun `a re-parsed row matches on dedupeKey despite a different id`() {
        // The whole point. Same transaction, fresh UUID from the re-parse, and the
        // backup holds a category the person fixed by hand.
        val plan = BackupMerge.plan(
            backup = listOf(backup("old-id", "k1", merchant = "Chai stall", category = "Food")),
            local = listOf(local("new-id", "k1", merchant = "SWIGGY", category = "Other")),
            backupSelfUid = null, localSelfUid = null, localSelfName = null,
        )

        assertThat(plan.restored).isEqualTo(0)
        assertThat(plan.reconciled).isEqualTo(1)
        val merged = plan.updates.single()
        assertThat(merged.id).isEqualTo("new-id")          // the local row, corrected
        assertThat(merged.merchant).isEqualTo("Chai stall")
        assertThat(merged.category).isEqualTo("Food")
    }

    @Test
    fun `running the same restore twice changes nothing the second time`() {
        val rows = listOf(local("a", "k1"), local("b", "k2"))
        val plan = BackupMerge.plan(
            backup = listOf(backup("a", "k1"), backup("b", "k2")),
            local = rows,
            backupSelfUid = null, localSelfUid = null, localSelfName = null,
        )

        assertThat(plan.touched).isEqualTo(0)
        assertThat(plan.untouched).isEqualTo(2)
        assertThat(plan.summaryLine()).contains("already here")
    }

    @Test
    fun `a deletion made on this phone is never undone`() {
        // The backup predates the deletion and still thinks the row is live. Restoring
        // it must not resurrect an entry somebody deliberately removed.
        val plan = BackupMerge.plan(
            backup = listOf(backup("a", "k1", deleted = false)),
            local = listOf(local("a", "k1", deleted = true)),
            backupSelfUid = null, localSelfUid = null, localSelfName = null,
        )

        assertThat(plan.updates.singleOrNull()?.deleted ?: true).isTrue()
    }

    @Test
    fun `a deletion recorded in the backup is applied`() {
        val plan = BackupMerge.plan(
            backup = listOf(backup("a", "k1", deleted = true)),
            local = listOf(local("a", "k1", deleted = false)),
            backupSelfUid = null, localSelfUid = null, localSelfName = null,
        )

        assertThat(plan.updates.single().deleted).isTrue()
    }

    @Test
    fun `only a hand-edited amount overrides what this phone read`() {
        val notEdited = BackupMerge.plan(
            backup = listOf(backup("a", "k1", amountPaise = 99900, amountEditedAt = null)),
            local = listOf(local("a", "k1", amountPaise = 45075)),
            backupSelfUid = null, localSelfUid = null, localSelfName = null,
        )
        // The parser's figure wins: an un-edited difference means the backup was made by
        // an older parser, not that a person disagreed.
        assertThat(notEdited.touched).isEqualTo(0)

        val edited = BackupMerge.plan(
            backup = listOf(backup("a", "k1", amountPaise = 99900, amountEditedAt = 7_000L)),
            local = listOf(local("a", "k1", amountPaise = 45075)),
            backupSelfUid = null, localSelfUid = null, localSelfName = null,
        )
        assertThat(edited.updates.single().amountPaise).isEqualTo(99900)
        assertThat(edited.updates.single().amountEditedAt).isEqualTo(7_000L)
    }

    @Test
    fun `a replacement phone takes ownership of its own rows only`() {
        val plan = BackupMerge.plan(
            backup = listOf(
                backup("a", "k1", ownerUid = "old-uid"),
                backup("b", "k2", ownerUid = "partner"),
            ),
            local = emptyList(),
            backupSelfUid = "old-uid",
            localSelfUid = "new-uid",
            localSelfName = "Manuel",
        )

        assertThat(plan.reattributed).isEqualTo(1)
        assertThat(plan.inserts.single { it.id == "a" }.ownerUid).isEqualTo("new-uid")
        // The partner's history stays the partner's.
        assertThat(plan.inserts.single { it.id == "b" }.ownerUid).isEqualTo("partner")
    }

    @Test
    fun `restoring onto the same phone re-points nothing`() {
        val plan = BackupMerge.plan(
            backup = listOf(backup("a", "k1", ownerUid = "me")),
            local = emptyList(),
            backupSelfUid = "me", localSelfUid = "me", localSelfName = "Manuel",
        )

        assertThat(plan.reattributed).isEqualTo(0)
        assertThat(plan.inserts.single().ownerUid).isEqualTo("me")
    }

    @Test
    fun `a reviewed entry is not pushed back into the review queue`() {
        val plan = BackupMerge.plan(
            backup = listOf(backup("a", "k1", needsReview = true)),
            local = listOf(local("a", "k1", needsReview = false)),
            backupSelfUid = null, localSelfUid = null, localSelfName = null,
        )

        assertThat(plan.touched).isEqualTo(0)
    }

    @Test
    fun `a live row wins the dedupe match over a tombstone sharing its key`() {
        val plan = BackupMerge.plan(
            backup = listOf(backup("x", "k1", category = "Food")),
            local = listOf(
                local("dead", "k1", deleted = true),
                local("live", "k1", category = "Other"),
            ),
            backupSelfUid = null, localSelfUid = null, localSelfName = null,
        )

        assertThat(plan.updates.single().id).isEqualTo("live")
    }

    @Test
    fun `an empty backup does not read as a failure`() {
        val plan = BackupMerge.plan(
            backup = emptyList(), local = listOf(local("a", "k1")),
            backupSelfUid = null, localSelfUid = null, localSelfName = null,
        )

        assertThat(plan.summaryLine()).isEqualTo("That backup holds no expenses.")
        assertThat(plan.changedNothing).isTrue()
    }

    @Test
    fun `every summary is a finished sentence`() {
        // Found on the phone: the caller appends a second sentence, and two branches
        // ended without a full stop — "That backup holds no expenses Sync to send this
        // to the other phone."
        val cases = listOf(
            BackupMerge.plan(emptyList(), emptyList(), null, null, null),
            BackupMerge.plan(listOf(backup("a", "k1")), listOf(local("a", "k1")), null, null, null),
            BackupMerge.plan(listOf(backup("a", "k1")), emptyList(), null, null, null),
            BackupMerge.plan(
                listOf(backup("a", "k1", category = "Food"), backup("b", "k2")),
                listOf(local("a2", "k1", category = "Other"), local("c", "k3")),
                null, null, null,
            ),
        )

        cases.forEach { assertThat(it.summaryLine()).endsWith(".") }
    }

    @Test
    fun `changedNothing is only true when nothing was written`() {
        val nothing = BackupMerge.plan(
            listOf(backup("a", "k1")), listOf(local("a", "k1")), null, null, null,
        )
        val something = BackupMerge.plan(listOf(backup("a", "k1")), emptyList(), null, null, null)

        assertThat(nothing.changedNothing).isTrue()
        assertThat(something.changedNothing).isFalse()
    }

    @Test
    fun `the summary counts each outcome and does not pluralise one`() {
        val plan = BackupMerge.plan(
            backup = listOf(backup("new", "k9"), backup("fix", "k1", category = "Food")),
            local = listOf(local("fix-local", "k1", category = "Other"), local("same", "k2")),
            backupSelfUid = null, localSelfUid = null, localSelfName = null,
        )

        val line = plan.summaryLine()
        assertThat(line).contains("1 expense")
        assertThat(line).contains("1 more")
        assertThat(line).doesNotContain("1 expenses")
    }
}
