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
    /**
     * Carried so a pending delete reaches the owner's phone at all — the request is
     * made on one device and answered on another.
     */
    val deleteRequestedBy: String? = null,
    val amountEditedAt: Long? = null,
    val counterpartyTail: String? = null,
    val needsReview: Boolean = false,
    /**
     * Both halves of a refund link, so the two phones agree about the month's spending.
     *
     * Defaulted, so a payload written by an older build decodes as "not a refund" rather than
     * failing — which is what keeps a mid-rollout household syncing.
     */
    val refundsTxnId: String? = null,
    val refundedPaise: Long = 0,
    /**
     * The bank's own message id, and the balance it quoted.
     *
     * Both are properties of the message a row came from, so the phone that received it
     * has the better copy — but a row the *peer* received and this phone did not has no
     * other source, and without them the partner's Accounts panel has no bank-stated
     * balance at all. Defaulted, so an older build's JSON still parses.
     */
    val bankMessageId: String? = null,
    val balancePaise: Long? = null,
    /**
     * The original bank message, so a mis-parse can be diagnosed on either phone.
     *
     * **This is the most sensitive field in the payload** — it carries the account tail
     * and the running balance in the bank's own words. On the folder and Bluetooth
     * transports it is AES-256-GCM encrypted per line and never appears in the clear.
     * On the Sheet transport it does: the script writes it into an `Original message`
     * column in plaintext. That is the stated cost of a ledger you can open and repair,
     * and it is the reason Sheet sync carries a warning the other transports do not.
     */
    val rawSms: String? = null,
)
