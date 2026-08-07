package com.manuel.ours.data.repo

import com.manuel.ours.data.db.PendingSenderDao
import com.manuel.ours.data.db.PendingSenderEntity
import com.manuel.ours.data.db.SharedRuleDao
import com.manuel.ours.data.db.SharedRuleEntity
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.sms.BankRules
import com.manuel.ours.data.sms.SmsParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Senders that write payment-shaped messages and that nobody has vouched for.
 *
 * The catch is deliberately wide and the *counting* is what waits. Adopting an unknown
 * sender on the shape of its message alone was measured against this household's 2,810
 * messages first: 99 unrecognised headers, six of them writing an amount with a settled
 * verb and a masked number, and none of the six a bank — an EPF passbook line, an Amazon
 * Pay balance, a fuel loyalty receipt, a Myntra gift card and two trading spams. That rule
 * read the EPF line as ₹61,989 of income and accepted an SMS advertising a shortened link.
 *
 * So nothing here is in any total. One tap settles a sender forever, in either direction.
 */
@Singleton
class PendingSenderRepository @Inject constructor(
    private val dao: PendingSenderDao,
    private val sharedRuleDao: SharedRuleDao,
    private val prefs: AppPrefs,
) {

    fun observeAll(): Flow<List<PendingSenderEntity>> = dao.observeAll()

    fun observeCount(): Flow<Int> = dao.observeCount()

    /**
     * Notes one payment-shaped message from a sender we cannot vouch for.
     *
     * Grouped by header rather than kept per message: 99 unknown headers carried 1,386
     * messages on the real phone, and a question per message is 1,386 decisions where a
     * question per sender is at most 99.
     */
    suspend fun record(result: SmsParser.Result.Unrecognised, receivedAt: Long) {
        // Already answered — either taught as a bank or tombstoned as not one. Asking
        // again would undo a decision the household has already made.
        if (answered(result.header)) return

        val existing = dao.find(result.header)
        dao.upsert(
            PendingSenderEntity(
                header = result.header,
                messageCount = (existing?.messageCount ?: 0) + 1,
                firstAt = existing?.firstAt ?: receivedAt,
                lastAt = maxOf(existing?.lastAt ?: 0L, receivedAt),
                sampleBody = result.body.take(SAMPLE_CHARS),
                lastAmountPaise = result.amountPaise ?: existing?.lastAmountPaise,
            )
        )
    }

    /**
     * "Yes, my bank." Writes the sender rule, which travels to the other phone over the
     * sheet, and takes effect on this one immediately rather than at the next sync.
     *
     * The messages themselves are not re-read here. A rescan does that, and it is offered
     * separately so the household can answer several senders and re-read once.
     */
    suspend fun confirm(header: String, bank: String) {
        writeRule(header, bank.trim().ifBlank { header })
        dao.delete(header)
    }

    /**
     * "Not a payment." The same rule with an empty value, which is this store's tombstone —
     * so the sender is not proposed again on its next message, on either phone.
     */
    suspend fun dismiss(header: String) {
        writeRule(header, "")
        dao.delete(header)
    }

    private suspend fun writeRule(header: String, value: String) {
        val key = header.uppercase().trim()
        sharedRuleDao.upsertAll(
            listOf(
                SharedRuleEntity(
                    type = RulesRepository.TYPE_SENDER,
                    ruleKey = key,
                    value = value,
                    updatedAt = System.currentTimeMillis(),
                    deviceId = prefs.deviceId(),
                )
            )
        )
        // Take effect now. Without this the answer sits in the table until the next sync
        // calls RulesRepository.apply(), and the very next message from that sender would
        // queue up again — which reads as the tap having done nothing.
        val taught = sharedRuleDao.all()
            .filter { it.type == RulesRepository.TYPE_SENDER && it.value.isNotBlank() }
            .associate { it.ruleKey to it.value }
        BankRules.setTaughtSenders(taught)
        BankRules.forgetDiscovered(key)
    }

    /** True once somebody has said yes or no to this header. */
    private suspend fun answered(header: String): Boolean =
        sharedRuleDao.all().any {
            it.type == RulesRepository.TYPE_SENDER &&
                it.ruleKey.equals(header, ignoreCase = true)
        }

    private companion object {
        /** Enough to recognise the message, not enough to keep a copy of the inbox. */
        const val SAMPLE_CHARS = 240
    }
}
