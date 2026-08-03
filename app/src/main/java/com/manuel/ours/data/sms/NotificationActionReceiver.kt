package com.manuel.ours.data.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.domain.model.Category
import com.manuel.ours.work.SyncWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles the one-tap category buttons on the new-expense notification.
 *
 * This is a **BroadcastReceiver, not an Activity**, for two reasons. Android 12+ bans
 * notification trampolines — an Activity that starts, does work and finishes is
 * blocked outright. And more importantly, categorising an expense should never yank
 * you out of whatever you were doing; the notification collapses and that's the end
 * of it.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var repository: TransactionRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CATEGORIZE) return

        val txnId = intent.getStringExtra(EXTRA_TXN_ID) ?: return
        val categoryName = intent.getStringExtra(EXTRA_CATEGORY) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, txnId.hashCode())

        // goAsync buys us ~10 seconds off the main thread — ample for one DB write,
        // and required because onReceive would otherwise return before the coroutine runs.
        val pending = goAsync()
        scope.launch {
            try {
                // learn = true is the point of the whole feature: correcting Swiggy
                // once means every future Swiggy lands in Food without asking.
                repository.recategorize(txnId, Category.fromNameOrOther(categoryName), learn = true)
                NotificationManagerCompat.from(context).cancel(notificationId)
                SyncWorker.syncNow(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_CATEGORIZE = "com.manuel.ours.action.CATEGORIZE"
        const val EXTRA_TXN_ID = "txn_id"
        const val EXTRA_CATEGORY = "category"
        const val EXTRA_NOTIFICATION_ID = "notification_id"

        fun intent(
            context: Context,
            txnId: String,
            category: Category,
            notificationId: Int,
        ): Intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_CATEGORIZE
            putExtra(EXTRA_TXN_ID, txnId)
            putExtra(EXTRA_CATEGORY, category.name)
            putExtra(EXTRA_NOTIFICATION_ID, notificationId)
        }
    }
}
