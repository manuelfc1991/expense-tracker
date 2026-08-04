package com.manuel.ours.data.sync

import com.manuel.ours.data.db.PeerCursorDao
import com.manuel.ours.data.db.PeerCursorEntity
import com.manuel.ours.data.db.SyncEventDao
import com.manuel.ours.data.db.SyncEventEntity
import com.manuel.ours.data.db.TransactionDao
import com.manuel.ours.data.db.TransactionEntity
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.sms.SmsDeduplicator
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs one sync round over whichever transport is offered.
 *
 * Transport-agnostic by construction: it hands out an encrypted blob and takes
 * encrypted blobs back. That seam is why deleting the Google Drive transport
 * entirely required no change to a single line of merge logic.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val eventDao: SyncEventDao,
    private val txnDao: TransactionDao,
    private val peerDao: PeerCursorDao,
    private val prefs: AppPrefs,
    private val clock: LamportClock,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun sync(transport: SyncTransport): SyncOutcome {
        return try {
            val deviceId = prefs.snapshot().deviceId

            // Pull first, so our own new events are minted with a clock that has
            // already seen everything the peer sent. Pull-then-push keeps Lamport
            // ordering sane across a long offline gap.
            val remote = transport.pull(deviceId).filter { it.deviceId != deviceId }
            var applied = 0

            if (remote.isNotEmpty()) {
                clock.observeAll(remote)
                applied = applyRemote(remote)

                remote.groupBy { it.deviceId }.forEach { (peer, events) ->
                    peerDao.upsert(
                        PeerCursorEntity(
                            deviceId = peer,
                            lastMergedLamport = events.maxOf { it.lamport },
                            lastSeenAt = System.currentTimeMillis(),
                        )
                    )
                }
                prefs.writeLamport(clock.current)
            }

            // Push only what has not been sent, in batches, marking each as it lands.
            //
            // One request for the whole backlog is fine until the backlog is large, and
            // then it fails permanently: a re-upload of ~470 events took the Apps Script
            // past the 40s read timeout, the push threw, `markPushed` never ran, and the
            // next sync retried the identical 470. Every attempt failed the same way, so
            // the sheet stayed frozen at whatever it held before while the app reported
            // success. Nothing in the log said otherwise, because the failure is carried
            // in the outcome rather than the worker result.
            //
            // Marking each batch as it lands is the other half. A failure now costs the
            // remaining batches, not the ones already delivered, so a big first sync
            // makes progress across several attempts instead of starting over.
            val unpushed = eventDao.unpushed().map { it.toSyncEvent() }
            var pushed = 0
            for (batch in unpushed.chunked(PUSH_BATCH)) {
                transport.push(deviceId, batch)
                eventDao.markPushed(batch.map { it.eventId })
                pushed += batch.size
            }

            prefs.setLastSync(System.currentTimeMillis(), transport.name)

            SyncOutcome(
                transport = transport.name,
                pulledEvents = remote.size,
                appliedEvents = applied,
                pushedEvents = pushed,
            )
        } catch (e: Exception) {
            SyncOutcome(transport.name, 0, 0, 0, e)
        }
    }

    /**
     * Applies peer events to Room. An event only wins if it beats what we already have
     * on (lamport, deviceId) — the exact same rule [LogMerger] uses, so applying
     * incrementally converges to the same state as merging the full history at once.
     */
    suspend fun applyRemote(events: List<SyncEvent>): Int {
        var applied = 0
        for (event in LogMerger.merge(events).values) {
            val existing = txnDao.getById(event.txnId)

            if (existing != null) {
                val existingWins = when {
                    existing.updatedAtLamport != event.lamport ->
                        existing.updatedAtLamport > event.lamport
                    else -> existing.updatedByDevice > event.deviceId
                }
                if (existingWins) continue
            }

            when (event.op) {
                SyncOp.DELETE -> {
                    if (existing != null) {
                        txnDao.softDelete(event.txnId, event.lamport, event.deviceId)
                        applied++
                    }
                }
                SyncOp.UPSERT -> {
                    val payload = event.payload ?: continue
                    txnDao.upsert(payload.toEntity(event, existing))
                    applied++
                }
            }
        }
        return applied
    }

    /** Removes superseded events from our own log once they have been pushed. */
    suspend fun compactOwnLog() {
        val all = eventDao.all().map { it.toSyncEvent() }
        if (all.size < COMPACT_THRESHOLD) return
        val keep = LogMerger.compact(all).map { it.eventId }.toHashSet()
        val superseded = all.filter { it.eventId !in keep }.map { it.eventId }
        if (superseded.isEmpty()) return
        // Delete the losers by name. Chunked because SQLite caps bound parameters, and
        // a long-lived log can supersede far more than that in one round.
        superseded.chunked(500).forEach { eventDao.deletePushedByIds(it) }
    }

    private fun SyncEventEntity.toSyncEvent() = SyncEvent(
        eventId = eventId,
        txnId = txnId,
        op = SyncOp.valueOf(op),
        lamport = lamport,
        deviceId = deviceId,
        ownerUid = ownerUid,
        wallClock = wallClock,
        payload = payloadJson?.let {
            runCatching { json.decodeFromString(SyncPayload.serializer(), it) }.getOrNull()
        },
    )

    private fun SyncPayload.toEntity(
        event: SyncEvent,
        existing: TransactionEntity?,
    ) = TransactionEntity(
        id = event.txnId,
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
        ownerUid = event.ownerUid,
        ownerName = ownerName,
        needsReview = needsReview,
        deleteRequestedBy = deleteRequestedBy,
        // Prefer whatever we already hold: our own copy came from this phone's
        // inbox and is authoritative. Fall back to the peer's only when we have none.
        rawSms = existing?.rawSms ?: rawSms,
        deleted = false,
        dedupeKey = SmsDeduplicator.bucketKey(amountPaise, occurredAt, refNo),
        dedupeAt = occurredAt,
        updatedAtLamport = event.lamport,
        updatedByDevice = event.deviceId,
    )

    companion object {
        /**
         * Events per push request. Small enough that a batch completes well inside the
         * transport's read timeout, large enough that a first sync is not hundreds of
         * round trips against an Apps Script that cold-starts each one.
         */
        private const val PUSH_BATCH = 100

        private const val COMPACT_THRESHOLD = 2000
    }
}
