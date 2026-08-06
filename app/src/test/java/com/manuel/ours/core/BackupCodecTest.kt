package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.backup.BackupCodec
import com.manuel.ours.data.backup.BackupFile
import com.manuel.ours.data.backup.BackupRead
import com.manuel.ours.data.backup.BackupRule
import com.manuel.ours.data.backup.toBackup
import com.manuel.ours.data.backup.toEntity
import com.manuel.ours.data.db.TransactionEntity
import org.junit.Test

/**
 * The file format, and every way reading one can go wrong.
 *
 * A backup is written once and read years later, on a phone that has been replaced, by
 * someone who has just lost data. Every failure here has to name itself rather than
 * produce a half-restore.
 */
class BackupCodecTest {

    private val txn = TransactionEntity(
        id = "t1",
        amountPaise = 45075,
        type = "DEBIT",
        merchant = "Chai stall, renamed by hand",
        category = "Food",
        occurredAt = 1_700_000_000_000L,
        accountTail = "3062",
        refNo = "REF9",
        bank = "KGBANK",
        note = "split with Beula",
        splitType = "SHARED",
        source = "MANUAL",
        ownerUid = "uid-1",
        ownerName = "Manuel",
        needsReview = false,
        rawSms = "Rs.450.75 debited...",
        deleted = false,
        deleteRequestedBy = null,
        amountEditedAt = 1_700_000_100_000L,
        counterpartyTail = "8891",
        balancePaise = 1234500,
        dedupeKey = "45075|1700000000|3062",
        dedupeAt = 1_700_000_000_000L,
        updatedAtLamport = 42L,
        updatedByDevice = "phone-a",
    )

    @Test
    fun `a transaction survives the round trip field for field`() {
        val file = BackupFile(createdAt = 1L, transactions = listOf(txn.toBackup()))

        val read = BackupCodec.decode(BackupCodec.encode(file))

        assertThat(read).isInstanceOf(BackupRead.Ok::class.java)
        val back = (read as BackupRead.Ok).file.transactions.single().toEntity()
        // Field-for-field, not merely "not null": the entity and the backup shape are
        // maintained separately on purpose, and this is what catches a dropped column.
        assertThat(back).isEqualTo(txn)
    }

    @Test
    fun `the whole file round trips`() {
        val file = BackupFile(
            createdAt = 99L,
            appVersionName = "5.13",
            appVersionCode = 54,
            selfUid = "uid-1",
            selfName = "Manuel",
            householdId = "house-1",
            trackingStartAt = 1_600_000_000_000L,
            transactions = listOf(txn.toBackup()),
            sharedRules = listOf(BackupRule("sender", "KGBANK", "Kerala Gramin Bank", 7L, "phone-a")),
        )

        val back = (BackupCodec.decode(BackupCodec.encode(file)) as BackupRead.Ok).file

        assertThat(back).isEqualTo(file)
    }

    @Test
    fun `a tombstone stays a tombstone`() {
        // If deleted were dropped in transit, every restore would resurrect everything
        // the household had ever thrown away.
        val file = BackupFile(createdAt = 1L, transactions = listOf(txn.copy(deleted = true).toBackup()))

        val back = (BackupCodec.decode(BackupCodec.encode(file)) as BackupRead.Ok).file

        assertThat(back.transactions.single().deleted).isTrue()
    }

    @Test
    fun `something that is not JSON is refused by name`() {
        val read = BackupCodec.decode("this is a photo, not a backup")

        assertThat(read).isInstanceOf(BackupRead.Unreadable::class.java)
        assertThat((read as BackupRead.Unreadable).detail).isEqualTo("this is not an Ours backup")
    }

    @Test
    fun `a rejection never quotes the file back at the reader`() {
        // Found on the phone, not here: picking a text file produced "Unexpected JSON
        // token at offset 6 ... JSON input: this is not a backup, it is a photo caption".
        // The parser's message carries a quotation of what it was given, and the file a
        // person picks by mistake may well be a bank statement.
        val secret = "ACCOUNT 3062 BALANCE 91234"

        val reads = listOf(
            BackupCodec.decode(secret),
            BackupCodec.decode("""{"note":"$secret"}"""),
            BackupCodec.decode("""{"format":"ours.backup","version":1,"x":"$secret"""),
        )

        reads.forEach { read ->
            val detail = (read as BackupRead.Unreadable).detail
            assertThat(detail).doesNotContain(secret)
            assertThat(detail).doesNotContain("offset")
        }
    }

    @Test
    fun `valid JSON that is not ours is refused`() {
        val read = BackupCodec.decode("""{"hello":"world"}""")

        assertThat(read).isInstanceOf(BackupRead.Unreadable::class.java)
        assertThat((read as BackupRead.Unreadable).detail).contains("not an Ours backup")
    }

    @Test
    fun `a file from a newer app is refused rather than half-read`() {
        val text = BackupCodec.encode(BackupFile(createdAt = 1L)).replace(
            "\"version\": ${BackupCodec.VERSION}",
            "\"version\": ${BackupCodec.VERSION + 5}",
        )

        val read = BackupCodec.decode(text)

        assertThat(read).isInstanceOf(BackupRead.TooNew::class.java)
        assertThat((read as BackupRead.TooNew).fileVersion).isEqualTo(BackupCodec.VERSION + 5)
    }

    @Test
    fun `an unknown field added by a later build does not break reading`() {
        val text = BackupCodec.encode(BackupFile(createdAt = 1L))
            .replaceFirst("{", """{"somethingNew": 1,""")

        assertThat(BackupCodec.decode(text)).isInstanceOf(BackupRead.Ok::class.java)
    }

    @Test
    fun `an empty file is valid and simply holds nothing`() {
        val read = BackupCodec.decode(BackupCodec.encode(BackupFile(createdAt = 1L)))

        assertThat((read as BackupRead.Ok).file.transactions).isEmpty()
    }
}
