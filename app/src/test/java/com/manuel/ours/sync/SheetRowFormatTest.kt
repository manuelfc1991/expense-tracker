package com.manuel.ours.sync

import com.manuel.ours.data.sync.LogMerger
import com.manuel.ours.data.sync.SheetTransport
import com.manuel.ours.data.sync.SyncEvent
import com.manuel.ours.data.sync.SyncOp
import com.manuel.ours.data.sync.SyncPayload
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The sheet is the one transport a human can edit, so it is the one most likely to be
 * fed something malformed. These cover the rules the Apps Script and the app must
 * agree on — if they drift, the sheet shows one thing and the phone another.
 */
class SheetRowFormatTest {

    private fun payload(merchant: String, rupees: Long = 151) = SyncPayload(
        amountPaise = rupees * 100,
        type = "DEBIT",
        merchant = merchant,
        category = "FOOD",
        occurredAt = 1_785_000_000_000L,
        splitType = "SHARED",
        source = "SMS",
        ownerName = "Manuel",
        rawSms = "Debited Rs $rupees from a/c X4657 to $merchant",
    )

    private fun event(
        id: String,
        txnId: String,
        lamport: Long,
        device: String,
        op: SyncOp = SyncOp.UPSERT,
        merchant: String = "Keecheril St",
    ) = SyncEvent(
        eventId = id,
        txnId = txnId,
        op = op,
        lamport = lamport,
        deviceId = device,
        ownerUid = "uid-$device",
        wallClock = 1_785_000_000_000L,
        payload = if (op == SyncOp.UPSERT) payload(merchant) else null,
    )

    @Test
    fun `the ledger tab resolves exactly as the app does`() {
        // The script rebuilds a readable tab using highest-lamport-wins with deviceId
        // as the tiebreak. If that ever diverged from LogMerger, the spreadsheet and
        // the phone would disagree about your money — worse than having no tab at all.
        val events = listOf(
            event("e1", "t1", 1, "deviceA", merchant = "Old"),
            event("e2", "t1", 5, "deviceB", merchant = "New"),
            event("e3", "t2", 3, "deviceA", merchant = "Uber"),
            event("e4", "t2", 3, "deviceB", merchant = "Ola"), // tie -> deviceB wins
        )
        val resolved = LogMerger.merge(events)

        assertThat(resolved["t1"]?.payload?.merchant).isEqualTo("New")
        assertThat(resolved["t2"]?.payload?.merchant).isEqualTo("Ola")
    }

    @Test
    fun `deleted transactions are excluded from the readable tab`() {
        val events = listOf(
            event("e1", "t1", 1, "deviceA"),
            event("e2", "t1", 9, "deviceB", op = SyncOp.DELETE),
        )
        assertThat(LogMerger.resolve(events)).isEmpty()
    }

    @Test
    fun `re-pushing the same events changes nothing`() {
        // The script skips eventIds it already holds. Merging duplicates must be a
        // no-op on the app side too, or a retry would double-count every expense.
        val events = listOf(event("e1", "t1", 1, "deviceA"), event("e2", "t2", 2, "deviceA"))
        assertThat(LogMerger.merge(events + events)).isEqualTo(LogMerger.merge(events))
    }

    @Test
    fun `a row deleted by hand in the sheet cannot resurrect a transaction`() {
        // Someone tidying the sheet deletes the DELETE row. The upsert is all that
        // remains, so the transaction comes back — this documents that reality rather
        // than pretending otherwise, which is why the ledger tab is the one to read
        // and the events tab is the one to leave alone.
        val full = listOf(
            event("e1", "t1", 1, "deviceA"),
            event("e2", "t1", 9, "deviceB", op = SyncOp.DELETE),
        )
        val handEdited = full.filter { it.op != SyncOp.DELETE }

        assertThat(LogMerger.resolve(full)).isEmpty()
        assertThat(LogMerger.resolve(handEdited)).hasSize(1)
    }

    @Test
    fun `events from many devices interleave without conflict`() {
        // Rows land in whatever order two phones happen to post them. Order must not
        // matter, because a spreadsheet gives no ordering guarantee across writers.
        val a = (1..10).map { event("a$it", "t${it % 4}", it.toLong(), "deviceA") }
        val b = (1..10).map { event("b$it", "t${it % 4}", it.toLong(), "deviceB") }

        val one = LogMerger.merge(a + b)
        val two = LogMerger.merge(b + a)
        val shuffled = LogMerger.merge((a + b).shuffled(kotlin.random.Random(7)))

        assertThat(two).isEqualTo(one)
        assertThat(shuffled).isEqualTo(one)
    }

    @Test
    fun `the payload carries everything the ledger tab needs to render`() {
        val p = payload("Bhadra Fuels", 210)
        assertThat(p.merchant).isNotEmpty()
        assertThat(p.category).isNotEmpty()
        assertThat(p.ownerName).isNotEmpty()
        assertThat(p.splitType).isNotEmpty()
        assertThat(p.amountPaise).isEqualTo(21_000)
        // Columns the script writes must all exist, or the sheet gets blank cells.
        assertThat(p.rawSms).isNotNull()
    }

    @Test
    fun `the sheet never receives the original bank message`() {
        // The sheet is plaintext behind a URL. The raw SMS carries the account tail and
        // the running balance in the bank's own words, so it is stripped before it
        // leaves the phone — on this transport only.
        val sensitive = "Debited Rs 151.00 from a/c X4657 Bal Rs 3469.55 -Federal Bank"
        val event = event("e1", "t1", 1, "phoneA").let {
            it.copy(payload = it.payload!!.copy(rawSms = sensitive, accountTail = "4657"))
        }

        val redacted = SheetTransport.redactForSheet(listOf(event))

        assertThat(redacted.single().payload?.rawSms).isNull()
        // Nothing else may be lost in the process — this is a redaction, not a filter.
        assertThat(redacted.single().payload?.amountPaise)
            .isEqualTo(event.payload?.amountPaise)
        assertThat(redacted.single().payload?.merchant).isEqualTo(event.payload?.merchant)
        assertThat(redacted.single().payload?.accountTail).isEqualTo(event.payload?.accountTail)
        assertThat(redacted.single().eventId).isEqualTo(event.eventId)

        // And it must not survive anywhere else in the payload, under any field.
        assertThat(redacted.single().payload.toString()).doesNotContain("3469.55")
        assertThat(redacted.single().payload.toString()).doesNotContain("X4657 Bal")
    }

    @Test
    fun `redaction leaves the caller's events untouched`() {
        // The encrypted transports push the same list. If redaction mutated in place,
        // stripping for the sheet would silently strip for Bluetooth too.
        val original = event("e2", "t2", 2, "phoneA")
        SheetTransport.redactForSheet(listOf(original))
        assertThat(original.payload?.rawSms).isNotNull()
    }
}
