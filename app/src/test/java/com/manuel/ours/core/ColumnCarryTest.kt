package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.backup.toBackup
import com.manuel.ours.data.backup.toEntity
import com.manuel.ours.data.db.TransactionEntity
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.TxnSource
import org.junit.Test

/**
 * Every column has to survive every crossing, and three of them did not.
 *
 * A column added to the schema has four places to be carried: the entity mappers, the sync
 * payload, and both directions of the backup format. Miss one and nothing fails — the data
 * is simply gone, at whichever moment that path happens to run.
 *
 * `bankMessageId` had missed the backup entirely, which is the worst place for it: the
 * documented recovery is restore-then-backfill, so a restored row with a null message id
 * dedupes against nothing and Kerala Gramin's two SMS for one debit both import. The
 * ₹8,955.79 double-count would return on the day the household needed the file.
 *
 * This test exists so the next added column is caught by a red test rather than by
 * somebody noticing money is wrong months later.
 */
class ColumnCarryTest {

    private val row = TransactionEntity(
        id = "t1",
        amountPaise = 177_800,
        type = "DEBIT",
        merchant = "Unknown payee",
        category = "TRANSFERS",
        occurredAt = 1_000L,
        accountTail = "3062",
        refNo = "658119750447",
        bankMessageId = "2644123773",
        bank = "Kerala Gramin Bank",
        note = "a note",
        splitType = SplitType.SHARED.name,
        source = TxnSource.SMS.name,
        ownerUid = "me",
        ownerName = "Manuel",
        needsReview = true,
        rawSms = "the original message",
        deleted = false,
        deletedAt = null,
        deleteRequestedBy = null,
        amountEditedAt = 5_000L,
        counterpartyTail = "4657",
        balancePaise = 4_924_490,
        refundsTxnId = "t0",
        refundedPaise = 50_000,
        dedupeKey = "ref:658119750447",
        dedupeAt = 2_000L,
        updatedAtLamport = 7L,
        updatedByDevice = "dev",
    )

    @Test
    fun `a backup round trip loses nothing`() {
        val back = row.toBackup().toEntity()

        // The three that were being dropped.
        assertThat(back.bankMessageId).isEqualTo("2644123773")
        assertThat(back.refundsTxnId).isEqualTo("t0")
        assertThat(back.refundedPaise).isEqualTo(50_000)

        // And everything else, so this test fails on the *next* forgotten column too.
        assertThat(back).isEqualTo(row)
    }

    /**
     * `dedupeAt` is not `occurredAt` and must never be recomputed from it.
     *
     * For a date-only card message `occurredAt` is midnight while `dedupeAt` is the real
     * delivery time, and `findNearby` matches on `dedupeAt` — so flattening one into the
     * other silently breaks dedup for exactly the rows a card bill produces.
     */
    @Test
    fun `a backup round trip keeps dedupeAt distinct from occurredAt`() {
        val back = row.toBackup().toEntity()
        assertThat(back.dedupeAt).isEqualTo(2_000L)
        assertThat(back.dedupeAt).isNotEqualTo(back.occurredAt)
    }
}
