package com.manuel.ours.data.sms

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.manuel.ours.data.prefs.AppPrefs
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

/**
 * The Play-Store-safe ingestion path.
 *
 * READ_SMS is a restricted permission that Google rejects for expense trackers, so
 * this reads the *notifications* banks post instead — same information, no restricted
 * permission. It needs the user to enable the listener in system settings once.
 */
@AndroidEntryPoint
class BankNotificationListener : NotificationListenerService() {

    @Inject lateinit var parser: SmsParser
    @Inject lateinit var repository: TransactionRepository
    @Inject lateinit var prefs: AppPrefs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        val sender = BANK_PACKAGES[packageName] ?: return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()

        // Prefer the expanded text — the collapsed line is often truncated mid-amount.
        val body = listOf(big, text).firstOrNull { it.isNotBlank() } ?: return
        val full = if (title.isNotBlank()) "$title. $body" else body

        scope.launch {
            if (prefs.ingestSource.first() != IngestSource.NOTIFICATION) return@launch
            when (val result = parser.parse(sender, full, sbn.postTime)) {
                is SmsParser.Result.Expense -> {
                    if (repository.ingestParsed(
                            result.txn,
                            com.manuel.ours.domain.model.TxnSource.NOTIFICATION,
                        ) != null
                    ) {
                        SyncWorker.syncNow(applicationContext)
                    }
                }
                else -> Unit
            }
        }
    }

    companion object {
        /**
         * Maps a bank app's package to the sender ID the parser already knows, so the
         * exact same rule table serves both ingestion paths.
         */
        private val BANK_PACKAGES = mapOf(
            "com.snapwork.hdfc" to "HDFCBK",
            "com.hdfcbank.payzapp" to "HDFCBK",
            "com.csam.icici.bank.imobile" to "ICICIB",
            "com.sbi.lotusintouch" to "SBIINB",
            "com.sbi.SBIFreedomPlus" to "SBIINB",
            "com.axis.mobile" to "AXISBK",
            "com.msf.kbank.mobile" to "KOTAKB",
            "com.google.android.apps.nbu.paisa.user" to "GPAYIN",
            "com.phonepe.app" to "PHONPE",
            "net.one97.paytm" to "PAYTM",
            "in.amazon.mShop.android.shopping" to "AMZNPY",
            "com.dreamplug.androidapp" to "CREDCL",
            "com.idfcfirstbank.optimus" to "IDFCFB",
            "com.fss.pnbpsp" to "PNBSMS",
            "com.canarabank.mobility" to "CANBNK",
            "com.bankofbaroda.mconnect" to "BOBTXN",
        )
    }
}
