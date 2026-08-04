package com.manuel.ours.data.sms

import com.manuel.ours.data.db.TransactionEntity
import kotlin.math.abs

/**
 * A single UPI payment routinely produces two SMS — one from the bank, one from the
 * UPI app — with slightly different wording and timestamps. Both are real messages;
 * only one is a real expense.
 *
 * Match on (amount, ±3 min, account tail or UPI ref), then keep whichever row carries
 * more information.
 */
object SmsDeduplicator {

    /** UPI refs are globally unique, so they win outright when present. */
    const val WINDOW_MS = 3 * 60 * 1000L
    private const val BUCKET_MS = WINDOW_MS

    /**
     * Coarse key used for the DB index. Two records in adjacent buckets can still be
     * duplicates, which is why [isDuplicate] does the precise check.
     */
    fun bucketKey(amountPaise: Long, occurredAt: Long, refNo: String?): String {
        if (!refNo.isNullOrBlank()) return "ref:$refNo"
        val bucket = occurredAt / BUCKET_MS
        return "amt:$amountPaise:t:$bucket"
    }

    /** All keys worth probing — covers the case where a pair straddles a bucket edge. */
    fun candidateKeys(amountPaise: Long, occurredAt: Long, refNo: String?): List<String> {
        if (!refNo.isNullOrBlank()) return listOf("ref:$refNo")
        val bucket = occurredAt / BUCKET_MS
        return listOf(
            "amt:$amountPaise:t:${bucket - 1}",
            "amt:$amountPaise:t:$bucket",
            "amt:$amountPaise:t:${bucket + 1}",
        )
    }

    fun isDuplicate(
        existing: TransactionEntity,
        incoming: SmsParser.ParsedTxn,
        existingDedupeAt: Long = existing.occurredAt,
    ): Boolean {
        if (existing.amountPaise != incoming.amountPaise) return false
        if (existing.type != incoming.type.name) return false

        val refMatch = !existing.refNo.isNullOrBlank() &&
            existing.refNo == incoming.refNo
        if (refMatch) return true

        val timeClose = abs(existingDedupeAt - incoming.dedupeAt) <= WINDOW_MS
        if (!timeClose) return false

        // Same amount within 3 minutes is already strong. If both name an account,
        // require them to agree; a mismatch means two genuinely different cards.
        val bothHaveTail = !existing.accountTail.isNullOrBlank() &&
            !incoming.accountTail.isNullOrBlank()
        return if (bothHaveTail) existing.accountTail == incoming.accountTail else true
    }

    /** Prefers the record that actually names a merchant, then the one with a ref. */
    fun richer(existing: TransactionEntity, incoming: SmsParser.ParsedTxn): Boolean {
        val existingScore = score(
            merchant = existing.merchant.takeIf { it.isNotBlank() && !it.equals("Unknown", true) },
            ref = existing.refNo,
            tail = existing.accountTail,
        )
        val incomingScore = score(incoming.merchant, incoming.refNo, incoming.accountTail)
        return incomingScore > existingScore
    }

    private fun score(merchant: String?, ref: String?, tail: String?): Int =
        (if (!merchant.isNullOrBlank()) 4 else 0) +
            (if (!ref.isNullOrBlank()) 2 else 0) +
            (if (!tail.isNullOrBlank()) 1 else 0)
}
