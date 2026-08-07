package com.manuel.ours.data.sms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.manuel.ours.MainActivity
import com.manuel.ours.OursApp
import com.manuel.ours.R
import com.manuel.ours.core.Money
import com.manuel.ours.data.db.ReminderDao
import com.manuel.ours.data.db.ReminderEntity
import com.manuel.ours.domain.BudgetAlerter
import com.manuel.ours.domain.model.Category
import com.manuel.ours.domain.model.Transaction
import com.manuel.ours.ui.capture.CaptureActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Everything that happens *after* a transaction is ingested, regardless of where it
 * came from.
 *
 * This used to live inside [SmsReceiver], which meant it only ever ran for the SMS
 * path. Switching the source to Notifications silently cost you the new-expense
 * notification, the one-tap categorize prompt, every budget alert, and bill reminders
 * entirely — [BankNotificationListener] saved the expense and stopped. Nothing said so;
 * the expenses still appeared in the list, so the app looked like it was working.
 *
 * Both ingestion paths now call the same object, so a feature can't exist for one
 * source and quietly not the other.
 */
@Singleton
class IngestNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderDao: ReminderDao,
    private val budgetAlerter: BudgetAlerter,
    private val prefs: com.manuel.ours.data.prefs.AppPrefs,
) {

    /**
     * A bill is money you *owe*, not money you spent, so it never becomes a
     * transaction. Storing it separately is what lets the app warn you before a due
     * date instead of only telling you afterwards.
     */
    suspend fun saveReminder(reminder: SmsParser.Result.BillReminder) {
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

    suspend fun notifyBudgetAlerts() {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        budgetAlerter.checkAndConsume().forEach { alert ->
            val notification = NotificationCompat.Builder(context, OursApp.CHANNEL_BUDGET)
                .setSmallIcon(com.manuel.ours.R.drawable.ic_notification)
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
     * Opens the capture prompt over whatever the phone is showing, when allowed.
     *
     * Four things all have to be true, and the last two cannot be cached: the popup is
     * switched on, the row is one nobody has categorised, Android still grants the
     * overlay permission (it is revocable in system settings at any time), and no screen
     * of this app is already in front — with Ours open the in-app sheet is showing the
     * same prompt, and a second window over it would be absurd.
     *
     * Never replaces the notification. If the launch is refused, or the user swipes the
     * popup away without deciding, the notification is still in the tray.
     */
    fun popUp(txn: Transaction) {
        val app = context.applicationContext as? OursApp ?: return
        if (app.inForeground) return
        if (!runBlocking { prefs.capturePopup.first() }) return
        if (!CaptureActivity.permitted(context)) return

        // Android can still refuse the launch — the permission is necessary, not
        // sufficient — and it throws rather than returning false when it does.
        runCatching { context.startActivity(CaptureActivity.intent(context, txn.id)) }
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
    fun notifyExpense(txn: Transaction, suggestions: List<Category>) {
        // Only for a row nobody has categorised. A payment the app already understands
        // has nothing to ask, and a popup that appears anyway is just an interruption.
        if (txn.needsReview) popUp(txn)

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

        // Drawn by us, not by Android.
        //
        // This is the surface the household sees most often — several times a day,
        // usually without opening the app — and a default notification makes it look
        // like a different product from the one it belongs to. RemoteViews is the only
        // way to style it: a notification is inflated by the system process, so Compose
        // is not available. The vocabulary that leaves is a caption, a figure and a
        // rule, which happens to be exactly what every screen here is made of.
        //
        // DecoratedCustomViewStyle keeps Android's own action buttons and chrome around
        // the custom body, so the one-tap category chips still look and behave like
        // system buttons rather than something reinvented badly.
        // Two layouts, not one. Android gives a custom *collapsed* view 64dp and simply
        // squeezes anything taller — no scroll, no scale, no warning — so sharing the
        // full-size layout between both states cost the collapsed one its lower half and
        // left the amount drawn as a row of glyph-bottoms.
        fun view(layout: Int) = RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.notif_amount, Money.format(txn.amountPaise))
            setTextViewText(R.id.notif_merchant, txn.merchant)
        }

        val collapsed = view(R.layout.notification_expense)
        val expanded = view(R.layout.notification_expense_big).apply {
            setTextViewText(
                R.id.notif_hint,
                if (suggestions.isNotEmpty()) "Tap a category below" else txn.category.label,
            )
        }

        val builder = NotificationCompat.Builder(context, OursApp.CHANNEL_EXPENSES_ACTIONABLE)
            .setSmallIcon(com.manuel.ours.R.drawable.ic_notification)
            // Kept for the lock screen, Wear and any surface that ignores custom views.
            .setContentTitle(title)
            .setContentText(subtitle)
            .setCustomContentView(collapsed)
            .setCustomBigContentView(expanded)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
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
                    category.label,
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
