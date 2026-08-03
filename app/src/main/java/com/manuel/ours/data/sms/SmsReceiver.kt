package com.manuel.ours.data.sms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.manuel.ours.MainActivity
import com.manuel.ours.OursApp
import com.manuel.ours.core.Money
import com.manuel.ours.data.db.ReminderDao
import com.manuel.ours.data.db.ReminderEntity
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.prefs.IngestSource
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.BudgetAlerter
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
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
    @Inject lateinit var reminderDao: ReminderDao
    @Inject lateinit var budgetAlerter: BudgetAlerter

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

                when (val result = parser.parse(sender, body, receivedAt)) {
                    is SmsParser.Result.Expense -> {
                        val txn = repository.ingestParsed(result.txn)
                        if (txn != null) {
                            val suggestions = if (txn.needsReview) {
                                repository.predictCategories(
                                    txn.merchant, txn.amountPaise, txn.type,
                                )
                            } else emptyList()
                            notifyExpense(context, txn, suggestions)
                            notifyBudgetAlerts(context)
                            SyncWorker.syncNow(context)
                        }
                    }
                    is SmsParser.Result.BillReminder -> saveReminder(result)
                    else -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * A bill is money you *owe*, not money you spent, so it never becomes a
     * transaction. Storing it separately is what lets the app warn you before a due
     * date instead of only telling you afterwards.
     */
    private suspend fun saveReminder(reminder: SmsParser.Result.BillReminder) {
        val dueAt = reminder.dueAt ?: return // no date means nothing to remind about
        reminderDao.upsert(
            ReminderEntity(
                // Deterministic id: the same message re-read during a rescan updates
                // the row rather than piling up duplicate reminders.
                id = "bill:${reminder.bank}:${reminder.amountPaise}:$dueAt",
                bank = reminder.bank,
                amountPaise = reminder.amountPaise,
                dueAt = dueAt,
                text = reminder.text,
                dismissed = false,
            )
        )
    }

    private suspend fun notifyBudgetAlerts(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        budgetAlerter.checkAndConsume().forEach { alert ->
            val notification = NotificationCompat.Builder(context, OursApp.CHANNEL_BUDGET)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(alert.title)
                .setContentText(alert.body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        alert.key.hashCode(),
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                )
                .build()
            runCatching { manager.notify(alert.key.hashCode(), notification) }
        }
    }

    /**
     * Heads-up notification with up to three one-tap category buttons.
     *
     * Three is not a design preference — Android renders at most three notification
     * actions, and anything beyond that is silently dropped. So the ranking in
     * [CategoryPredictor] has to be good: these three chips are the entire interface.
     *
     * When the transaction already has a confident category, no buttons are shown.
     * The prompt is meant to fade away as the app learns, not become a permanent tax
     * on every purchase.
     */
    private fun notifyExpense(
        context: Context,
        txn: Transaction,
        suggestions: List<Category>,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val notificationId = txn.id.hashCode()

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_TXN_ID, txn.id)
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = "${Money.format(txn.amountPaise)} · ${txn.merchant}"
        val subtitle = when {
            suggestions.isNotEmpty() -> "Tap a category, or open for the full list"
            else -> txn.category.label
        }

        val builder = NotificationCompat.Builder(context, OursApp.CHANNEL_EXPENSES_ACTIONABLE)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

        suggestions.forEachIndexed { index, category ->
            val actionIntent = PendingIntent.getBroadcast(
                context,
                // Request code must be unique per (transaction, category) or the
                // extras of the first PendingIntent get reused for all three buttons
                // and every chip files the expense under the same category.
                notificationId * 31 + index,
                NotificationActionReceiver.intent(context, txn.id, category, notificationId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0,
                    category.label.substringBefore(" &"),
                    actionIntent,
                ).build()
            )
        }

        try {
            manager.notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
        }
    }
}
