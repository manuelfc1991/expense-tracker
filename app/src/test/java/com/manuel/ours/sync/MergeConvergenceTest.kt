package com.manuel.ours.sync

import com.manuel.ours.data.sync.LamportClock
import com.manuel.ours.data.sync.LogMerger
import com.manuel.ours.data.sync.SyncEvent
import com.manuel.ours.data.sync.SyncOp
import com.manuel.ours.data.sync.SyncPayload
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.random.Random

/**
 * The property that makes serverless sync safe: **both phones reach identical state
 * regardless of the order events arrive in**.
 *
 * If this suite passes, no transport can corrupt the merge — Drive, Bluetooth, a USB
 * stick, carrier pigeon. If it fails, no amount of careful networking will save it.
 */
class MergeConvergenceTest {

    private fun payload(merchant: String, amount: Long = 10_000) = SyncPayload(
        amountPaise = amount,
        type = "DEBIT",
        merchant = merchant,
        category = "FOOD",
        occurredAt = 1_720_000_000_000L,
        splitType = "SHARED",
        source = "SMS",
        ownerName = "Test",
    )

    private fun event(
        txnId: String,
        lamport: Long,
        device: String,
        op: SyncOp = SyncOp.UPSERT,
        merchant: String = "Merchant",
        eventId: String = "$device-$txnId-$lamport-${op.name}",
    ) = SyncEvent(
        eventId = eventId,
        txnId = txnId,
        op = op,
        lamport = lamport,
        deviceId = device,
        ownerUid = "uid-$device",
        wallClock = 0L,
        payload = if (op == SyncOp.UPSERT) payload(merchant) else null,
    )

    @Test
    fun `merge is order independent`() {
        val events = listOf(
            event("t1", 1, "deviceA", merchant = "Swiggy"),
            event("t1", 5, "deviceB", merchant = "Zomato"),
            event("t2", 2, "deviceA", merchant = "Uber"),
            event("t1", 3, "deviceA", merchant = "Blinkit"),
            event("t2", 7, "deviceB", merchant = "Ola"),
        )

        val forward = LogMerger.merge(events)
        val reversed = LogMerger.merge(events.reversed())
        val shuffled = LogMerger.merge(events.shuffled(Random(42)))

        assertThat(forward).isEqualTo(reversed)
        assertThat(forward).isEqualTo(shuffled)
        assertThat(forward["t1"]?.payload?.merchant).isEqualTo("Zomato")
        assertThat(forward["t2"]?.payload?.merchant).isEqualTo("Ola")
    }

    @Test
    fun `higher lamport always wins`() {
        val older = event("t1", 3, "deviceA", merchant = "Old")
        val newer = event("t1", 9, "deviceB", merchant = "New")
        assertThat(LogMerger.merge(listOf(older, newer))["t1"]?.payload?.merchant)
            .isEqualTo("New")
    }

    @Test
    fun `equal lamport is broken by device id deterministically`() {
        val a = event("t1", 5, "deviceA", merchant = "FromA")
        val b = event("t1", 5, "deviceB", merchant = "FromB")

        // deviceB > deviceA lexically, so B wins — and both phones compute that
        // identically without any coordination.
        assertThat(LogMerger.merge(listOf(a, b))["t1"]?.payload?.merchant).isEqualTo("FromB")
        assertThat(LogMerger.merge(listOf(b, a))["t1"]?.payload?.merchant).isEqualTo("FromB")
    }

    @Test
    fun `both phones edit the same transaction while offline`() {
        // Shared history, then they diverge.
        val shared = event("t1", 1, "deviceA", merchant = "Original")

        val myEdit = event("t1", 2, "deviceA", merchant = "My edit")
        val herEdit = event("t1", 3, "deviceB", merchant = "Her edit")

        val myView = LogMerger.merge(listOf(shared, myEdit, herEdit))
        val herView = LogMerger.merge(listOf(shared, herEdit, myEdit))

        assertThat(myView).isEqualTo(herView)
        assertThat(myView["t1"]?.payload?.merchant).isEqualTo("Her edit")
    }

    @Test
    fun `delete beats an earlier edit`() {
        val edit = event("t1", 4, "deviceA")
        val delete = event("t1", 8, "deviceB", op = SyncOp.DELETE)

        val resolved = LogMerger.resolve(listOf(edit, delete))
        assertThat(resolved).isEmpty()
    }

    @Test
    fun `an edit after a delete resurrects the transaction`() {
        // Deliberate: if you delete on one phone and immediately re-edit on the other,
        // the later intent wins. Silently discarding the edit would lose real data.
        val delete = event("t1", 4, "deviceA", op = SyncOp.DELETE)
        val edit = event("t1", 9, "deviceB", merchant = "Restored")

        val resolved = LogMerger.resolve(listOf(delete, edit))
        assertThat(resolved).hasSize(1)
        assertThat(resolved.first().payload?.merchant).isEqualTo("Restored")
    }

    @Test
    fun `duplicate log delivery is idempotent`() {
        val events = listOf(
            event("t1", 1, "deviceA"),
            event("t2", 2, "deviceB"),
        )
        val once = LogMerger.merge(events)
        val twice = LogMerger.merge(events + events)
        val thrice = LogMerger.merge(events + events + events)

        assertThat(twice).isEqualTo(once)
        assertThat(thrice).isEqualTo(once)
    }

    @Test
    fun `out of order arrival converges`() {
        val stream = (1..40).map { i ->
            event(
                txnId = "t${i % 7}",
                lamport = i.toLong(),
                device = if (i % 2 == 0) "deviceA" else "deviceB",
                merchant = "M$i",
            )
        }

        val inOrder = LogMerger.merge(stream)
        repeat(20) { seed ->
            assertThat(LogMerger.merge(stream.shuffled(Random(seed)))).isEqualTo(inOrder)
        }
    }

    @Test
    fun `partial delivery then catch up equals full delivery`() {
        val all = (1..30).map { i ->
            event("t${i % 5}", i.toLong(), if (i % 3 == 0) "deviceA" else "deviceB", merchant = "M$i")
        }
        val firstHalf = all.take(15)
        val secondHalf = all.drop(15)

        // Applying incrementally must equal applying everything at once.
        val incremental = LogMerger.merge(
            LogMerger.merge(firstHalf).values + secondHalf
        )
        assertThat(incremental).isEqualTo(LogMerger.merge(all))
    }

    @Test
    fun `compaction preserves resolved state`() {
        val all = (1..50).map { i ->
            event("t${i % 6}", i.toLong(), if (i % 2 == 0) "deviceA" else "deviceB", merchant = "M$i")
        } + event("t3", 99, "deviceA", op = SyncOp.DELETE)

        val compacted = LogMerger.compact(all)

        assertThat(compacted.size).isLessThan(all.size)
        assertThat(LogMerger.merge(compacted)).isEqualTo(LogMerger.merge(all))
        // The tombstone survives compaction, otherwise a stale peer log resurrects t3.
        assertThat(compacted.any { it.txnId == "t3" && it.op == SyncOp.DELETE }).isTrue()
    }

    @Test
    fun `lamport clock advances past what it observes`() {
        val clock = LamportClock(initial = 5)
        clock.observe(12)
        assertThat(clock.current).isEqualTo(12)
        assertThat(clock.tick()).isEqualTo(13)

        // An older remote value must never drag the clock backwards.
        clock.observe(3)
        assertThat(clock.current).isEqualTo(13)
    }

    @Test
    fun `clock skew between devices does not affect ordering`() {
        // deviceB's wall clock is a day behind, but its event is logically later.
        val a = event("t1", 5, "deviceA", merchant = "Earlier logically")
            .copy(wallClock = 2_000_000_000_000L)
        val b = event("t1", 6, "deviceB", merchant = "Later logically")
            .copy(wallClock = 1_000_000_000_000L)

        assertThat(LogMerger.merge(listOf(a, b))["t1"]?.payload?.merchant)
            .isEqualTo("Later logically")
    }

    @Test
    fun `two devices simulating a full exchange converge`() {
        val clockA = LamportClock()
        val clockB = LamportClock()
        val logA = mutableListOf<SyncEvent>()
        val logB = mutableListOf<SyncEvent>()

        // Both phones record expenses independently, offline.
        repeat(5) { i ->
            logA += event("a$i", clockA.tick(), "deviceA", merchant = "A-merchant-$i")
        }
        repeat(5) { i ->
            logB += event("b$i", clockB.tick(), "deviceB", merchant = "B-merchant-$i")
        }

        // They meet. Each pulls the other's log and observes its clock.
        clockA.observeAll(logB)
        clockB.observeAll(logA)

        // A edits one of B's transactions; B deletes one of A's.
        logA += event("b2", clockA.tick(), "deviceA", merchant = "Fixed by A")
        logB += event("a3", clockB.tick(), "deviceB", op = SyncOp.DELETE)

        // Second exchange.
        val stateA = LogMerger.merge(logA + logB)
        val stateB = LogMerger.merge(logB + logA)

        assertThat(stateA).isEqualTo(stateB)
        assertThat(stateA["b2"]?.payload?.merchant).isEqualTo("Fixed by A")
        assertThat(stateA["a3"]?.op).isEqualTo(SyncOp.DELETE)
        assertThat(LogMerger.resolve(logA + logB)).hasSize(9) // 10 created, 1 deleted
    }
}
