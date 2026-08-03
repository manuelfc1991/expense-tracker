package com.manuel.ours.data.sync

import kotlinx.serialization.Serializable

enum class SyncOp { UPSERT, DELETE }

/**
 * One immutable entry in a device's append-only log.
 *
 * Ordering uses a **Lamport clock, not wall-clock time**. Two phones' clocks drift by
 * seconds to minutes; a Lamport counter (`local = max(local, maxSeen) + 1` on every
 * merge) gives a total order both devices agree on without trusting either clock.
 * [wallClock] is carried for display only and never used for conflict resolution.
 */
@Serializable
data class SyncEvent(
    val eventId: String,
    val txnId: String,
    val op: SyncOp,
    val lamport: Long,
    val deviceId: String,
    val ownerUid: String,
    val wallClock: Long,
    /** Null for DELETE tombstones. */
    val payload: SyncPayload? = null,
)

/**
 * The transaction as it crosses the wire.
 *
 * [rawSms] is included at the user's explicit request, so wrong parses can be
 * diagnosed by reading the sheet. Be aware of what that means: the original bank
 * message carries your **account tail and running balance**, and the sheet transport
 * writes plaintext. The Bluetooth and folder transports still encrypt it.
 */
@Serializable
data class SyncPayload(
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
    val ownerName: String,
    val needsReview: Boolean = false,
    /** Original bank message. Plaintext in the sheet — see the class note. */
    val rawSms: String? = null,
)
