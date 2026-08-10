package com.manuel.ours.core

import com.google.common.truth.Truth.assertThat
import com.manuel.ours.data.backup.toBackup
import com.manuel.ours.data.backup.toEntity
import com.manuel.ours.data.db.TransactionEntity
import com.manuel.ours.data.sync.SyncPayload
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.TxnSource
import com.manuel.ours.domain.model.TxnType
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * That a stated move survives leaving this phone and coming back.
 *
 * Two exits and they fail differently. A **backup** is the documented answer to "the phone is
 * gone" — a field the format forgets is a field that does not come back, and the link between
 * two legs is not something a restore could re-derive, because deriving it from matching amounts
 * is precisely the inference this column exists to replace.
 *
 * **Sync** is the more interesting one, because the two phones will not be on the same version
 * at the same time. Manuel's updates first; the partner's is on the previous build for however
 * long it takes her to accept it. Both directions of that gap are pinned below, and neither may
 * be allowed to fail the whole sync — a household that stops syncing because one phone is a
 * version behind is worse off than one that syncs slightly less information.
 */
class MoveTravelsTest {

    /** The two phones' shared reader: additions are forgiven, which is the whole mechanism. */
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun leg(id: String, peer: String?) = TransactionEntity(
        id = id,
        amountPaise = 468_41L,
        type = TxnType.CREDIT.name,
        merchant = "Kerala Gramin ···3062 → ICICI ···3008",
        category = Category.SELF_TRANSFER.name,
        occurredAt = 1_800_000_000_000L,
        accountTail = "3008",
        refNo = null,
        bank = "ICICI Bank",
        note = null,
        splitType = SplitType.SHARED.name,
        source = TxnSource.MANUAL.name,
        ownerUid = "manuel",
        ownerName = "Manuel",
        needsReview = false,
        rawSms = null,
        deleted = false,
        transferPeerId = peer,
        dedupeKey = "move:$id",
        dedupeAt = 1_800_000_000_000L,
        updatedAtLamport = 4L,
        updatedByDevice = "dev",
    )

    // ── Backup ────────────────────────────────────────────────────────────────────────

    /**
     * Out to a file and back unchanged.
     *
     * The backup classes deliberately duplicate the entity fields rather than serialising the
     * entity, so that adding a column is a compile error here rather than a silent omission —
     * this is the test that makes that promise mean something for this column.
     */
    @Test
    fun `a move survives a backup round trip`() {
        val restored = leg("in", "out").toBackup().toEntity()
        assertThat(restored.transferPeerId).isEqualTo("out")
        assertThat(restored).isEqualTo(leg("in", "out"))
    }

    /** And a row that is not part of a move comes back as one that is not part of a move. */
    @Test
    fun `an ordinary row comes back with no link`() {
        assertThat(leg("solo", null).toBackup().toEntity().transferPeerId).isNull()
    }

    /**
     * A backup written before this column existed still restores.
     *
     * The file on somebody's Drive was written months ago and is exactly what they reach for on
     * the day the phone is gone. Refusing it because it lacks a field added since would turn the
     * safety net into a second way to lose everything.
     */
    @Test
    fun `a backup written before the column existed still loads`() {
        val old = json.encodeToString(
            com.manuel.ours.data.backup.BackupTxn.serializer(),
            leg("old", "peer").toBackup(),
        ).replace(Regex(""","transferPeerId":"peer""""), "")
        assertThat(old).doesNotContain("transferPeerId")

        val decoded = json.decodeFromString(
            com.manuel.ours.data.backup.BackupTxn.serializer(), old,
        )
        assertThat(decoded.transferPeerId).isNull()
        assertThat(decoded.amountPaise).isEqualTo(468_41L)
    }

    // ── Sync, across a version gap ────────────────────────────────────────────────────

    /** The ordinary case: both phones updated, and the link travels. */
    @Test
    fun `the link travels between two phones on this version`() {
        val encoded = json.encodeToString(SyncPayload.serializer(), payload("out"))
        assertThat(encoded).contains("\"transferPeerId\":\"out\"")
        assertThat(json.decodeFromString(SyncPayload.serializer(), encoded).transferPeerId)
            .isEqualTo("out")
    }

    /**
     * A payload from the *older* phone, which has never heard of the field, decodes as "not part
     * of a move" rather than failing. That is what `= null` on the field buys.
     */
    @Test
    fun `a payload from the previous version decodes as not a move`() {
        val old = json.encodeToString(SyncPayload.serializer(), payload("out"))
            .replace(""","transferPeerId":"out"""", "")
        assertThat(old).doesNotContain("transferPeerId")
        assertThat(json.decodeFromString(SyncPayload.serializer(), old).transferPeerId).isNull()
    }

    /**
     * And the reverse, which is the direction that actually happens: this phone sends a move to
     * one still on the previous build. It must not reject the payload over a field it has no
     * property for.
     *
     * What she loses is the *link*, not the money. `category` travels on both legs, and
     * `SELF_TRANSFER` is what keeps the pair out of spending and out of income — so her totals
     * are right. What she does not get is the two rows deleting together, until she updates.
     */
    @Test
    fun `the previous version accepts a payload carrying a field it does not know`() {
        val fromNewer = json.encodeToString(SyncPayload.serializer(), payload("out"))
        // Standing in for the older build's class: the same reader, without the property.
        val asOlderPhoneSeesIt =
            json.decodeFromString(LegacyPayload.serializer(), fromNewer)
        assertThat(asOlderPhoneSeesIt.category).isEqualTo(Category.SELF_TRANSFER.name)
        assertThat(asOlderPhoneSeesIt.amountPaise).isEqualTo(468_41L)
    }

    private fun payload(peer: String) = SyncPayload(
        amountPaise = 468_41L,
        type = TxnType.CREDIT.name,
        merchant = "Kerala Gramin ···3062 → ICICI ···3008",
        category = Category.SELF_TRANSFER.name,
        occurredAt = 1_800_000_000_000L,
        accountTail = "3008",
        bank = "ICICI Bank",
        splitType = SplitType.SHARED.name,
        source = TxnSource.MANUAL.name,
        ownerName = "Manuel",
        transferPeerId = peer,
    )

    /** The previous build's payload shape, as far as this matters: no `transferPeerId`. */
    @kotlinx.serialization.Serializable
    private data class LegacyPayload(
        val amountPaise: Long,
        val type: String,
        val merchant: String,
        val category: String,
        val occurredAt: Long,
        val splitType: String,
        val source: String,
        val ownerName: String,
    )
}
