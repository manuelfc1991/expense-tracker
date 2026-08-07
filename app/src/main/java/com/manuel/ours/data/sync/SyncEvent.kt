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
 * [rawSms] rides along so a wrong parse can be diagnosed on the other phone.
 *
 * It no longer reaches the sheet: `SheetTransport.push` redacts it before serialising.
 * The Bluetooth and folder transports carry it, encrypted. This comment used to say the
 * sheet wrote it in plaintext, which stopped being true when `redactForSheet` was added —
 * and a stale warning about privacy is the kind that gets "corrected" back into a leak.
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
     *
     * It does **not** reach the sheet at all: `SheetTransport.redactForSheet` strips it on
     * the way out. It used to be written into an `Original message` column, and that is
     * what the paragraph here described; the redaction replaced it, and the description
     * outlived the behaviour.
     */
    val rawSms: String? = null,
)
