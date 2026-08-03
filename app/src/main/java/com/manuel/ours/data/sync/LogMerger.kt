package com.manuel.ours.data.sync

/**
 * The whole of conflict resolution.
 *
 * Because every device writes only to its own log, two devices never contend for the
 * same file — there is nothing to lock and nothing to reconcile at the *file* level.
 * All that remains is picking a winner per transaction, which is a deterministic
 * function of the event set:
 *
 *   winner = max by (lamport, deviceId)
 *
 * Deterministic means order-independent: feed the same events in any order, on either
 * phone, and you land on byte-identical state. That property is what
 * `MergeConvergenceTest` exercises.
 */
object LogMerger {

    /** Latest event per txnId. Duplicate eventIds collapse harmlessly. */
    fun merge(events: Iterable<SyncEvent>): Map<String, SyncEvent> {
        val winners = HashMap<String, SyncEvent>()
        for (event in events) {
            val existing = winners[event.txnId]
            if (existing == null || wins(event, existing)) {
                winners[event.txnId] = event
            }
        }
        return winners
    }

    /**
     * Resolved state: winners with tombstones dropped. This is what the UI shows.
     */
    fun resolve(events: Iterable<SyncEvent>): List<SyncEvent> =
        merge(events).values
            .filter { it.op == SyncOp.UPSERT && it.payload != null }
            .sortedByDescending { it.payload!!.occurredAt }

    /**
     * `a` beats `b`. Higher lamport wins; a tie is broken by deviceId string order so
     * both phones reach the same answer without talking to each other. eventId is the
     * final tiebreak for the pathological case of one device emitting two events at
     * the same tick.
     */
    fun wins(a: SyncEvent, b: SyncEvent): Boolean = when {
        a.lamport != b.lamport -> a.lamport > b.lamport
        a.deviceId != b.deviceId -> a.deviceId > b.deviceId
        else -> a.eventId > b.eventId
    }

    /**
     * Compaction: the smallest event set that reproduces the same resolved state.
     * Keeps tombstones — dropping them would resurrect deleted rows when an old log
     * from a long-offline device finally arrives.
     */
    fun compact(events: Iterable<SyncEvent>): List<SyncEvent> =
        merge(events).values.sortedBy { it.lamport }
}
