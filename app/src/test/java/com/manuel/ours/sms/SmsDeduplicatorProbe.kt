package com.manuel.ours.sms

import com.manuel.ours.data.db.AppDatabase
import com.manuel.ours.data.db.TransactionEntity
import com.manuel.ours.data.sms.SmsDeduplicator
import com.manuel.ours.data.sms.SmsParser
import com.manuel.ours.domain.model.SplitType
import com.manuel.ours.domain.model.TxnSource

/**
 * Reproduces the ingest path's dedup decision against a real database, without
 * dragging Hilt, DataStore and the Lamport clock into a unit test. If the production
 * repository changes how it keys duplicates, this must change with it.
 */
object SmsDeduplicatorProbe {

    suspend fun findDuplicate(
        db: AppDatabase,
        parsed: SmsParser.ParsedTxn,
    ): TransactionEntity? {
        for (key in SmsDeduplicator.candidateKeys(
            parsed.amountPaise, parsed.dedupeAt, parsed.refNo,
        )) {
            val existing = db.transactionDao().findByDedupeKey(key) ?: continue
            if (SmsDeduplicator.isDuplicate(existing, parsed, existing.dedupeAt)) return existing
        }
        return null
    }

    fun toEntity(parsed: SmsParser.ParsedTxn, lamport: Long) = TransactionEntity(
        id = "txn-${parsed.rawBody.hashCode()}",
        amountPaise = parsed.amountPaise,
        type = parsed.type.name,
        merchant = parsed.merchant ?: "Unknown payee",
        category = "OTHER",
        occurredAt = parsed.occurredAt,
        accountTail = parsed.accountTail,
        refNo = parsed.refNo,
        bank = parsed.bank,
        note = null,
        splitType = SplitType.SHARED.name,
        source = TxnSource.SMS.name,
        ownerUid = "me",
        ownerName = "Me",
        needsReview = false,
        rawSms = parsed.rawBody,
        deleted = false,
        dedupeKey = SmsDeduplicator.bucketKey(
            parsed.amountPaise, parsed.dedupeAt, parsed.refNo,
        ),
        dedupeAt = parsed.dedupeAt,
        updatedAtLamport = lamport,
        updatedByDevice = "test-device",
    )
}
