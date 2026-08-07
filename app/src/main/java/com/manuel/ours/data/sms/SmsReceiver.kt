package com.manuel.ours.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.PendingSenderRepository
import com.manuel.ours.data.prefs.IngestSource
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.work.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Live ingestion. Parses on arrival and notifies so a mis-parse can be fixed immediately. */
@AndroidEntryPoint
class SmsReceiver : BroadcastReceiver() {

    @Inject lateinit var parser: SmsParser
    @Inject lateinit var repository: TransactionRepository
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var notifier: IngestNotifier
    @Inject lateinit var pendingSenders: PendingSenderRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        // Multipart messages arrive as several PDUs of one logical SMS — join them
        // before parsing or a long alert gets truncated mid-amount.
        val sender = messages.first().displayOriginatingAddress ?: return
        val body = messages.joinToString("") { it.displayMessageBody.orEmpty() }
        val receivedAt = messages.first().timestampMillis

        val pending = goAsync()
        scope.launch {
            try {
                if (prefs.ingestSource.first() != IngestSource.SMS) return@launch

                when (val result = parser.parse(sender, body, receivedAt, prefs.readEveryPaymentOnce())) {
                    is SmsParser.Result.Expense -> {
                        val txn = repository.ingestParsed(result.txn)
                        if (txn != null) {
                            val suggestions = if (txn.needsReview) {
                                repository.predictCategories(
                                    txn.merchant, txn.amountPaise, txn.type,
                                )
                            } else emptyList()
                            notifier.notifyExpense(txn, suggestions)
                            notifier.notifyBudgetAlerts()
                            SyncWorker.syncNow(context)
                        }
                    }
                    is SmsParser.Result.BillReminder -> notifier.saveReminder(result)
                    is SmsParser.Result.Unrecognised ->
                        pendingSenders.record(result, receivedAt)
                    else -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }

}
