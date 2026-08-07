package com.manuel.ours.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.PendingSenderRepository
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.data.sms.SmsParser
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * First-launch backfill: reads the last 6 months of inbox SMS and parses them.
 *
 * Reports progress so onboarding can show a real bar rather than a spinner — on a
 * phone with years of messages this genuinely takes a while, and an unexplained wait
 * reads as a hang.
 */
@HiltWorker
class SmsBackfillWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val parser: SmsParser,
    private val repository: TransactionRepository,
    private val prefs: AppPrefs,
    private val pendingSenders: PendingSenderRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Six months of SMS can take minutes. Without foreground status Android
        // reclaims the process partway and the import silently stops early.
        runCatching { setForeground(foregroundInfo()) }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return Result.failure(workDataOf(KEY_ERROR to "READ_SMS not granted"))
        }

        val monthsBack = inputData.getInt(KEY_MONTHS, DEFAULT_MONTHS)
        // The tracking-start date wins over the rolling window. Without this the
        // "safety net" rescan in OursApp would quietly re-import every month the user
        // has retired, every single launch.
        // Read once for the whole scan rather than per message: it cannot change
        // mid-backfill, and a DataStore read per SMS over six months is thousands.
        val readEveryPayment = prefs.readEveryPaymentOnce()
        val since = maxOf(
            System.currentTimeMillis() - monthsBack * 30L * 24 * 60 * 60 * 1000,
            prefs.trackingStartAtOnce(),
        )

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
        )

        var scanned = 0
        var imported = 0

        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { cursor ->
            val total = cursor.count.coerceAtLeast(1)
            val addressIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (cursor.moveToNext()) {
                // retry, NOT failure. Android stops long-running workers routinely —
                // screen off, memory pressure, the 10-minute cap. Returning failure
                // ends the job for good, which is why a scan that got interrupted
                // left six months of history permanently truncated at whatever day it
                // happened to reach. Restarting from the top is cheap: dedup makes
                // re-reading already-imported messages a no-op.
                if (isStopped) return Result.retry()

                val sender = cursor.getString(addressIdx) ?: continue
                val body = cursor.getString(bodyIdx) ?: continue
                val date = cursor.getLong(dateIdx)

                when (val result = parser.parse(sender, body, date, readEveryPayment)) {
                    is SmsParser.Result.Expense -> {
                        if (repository.ingestParsed(result.txn) != null) imported++
                    }
                    // Payment-shaped, from a sender nobody has vouched for. Held for a
                    // one-tap answer rather than discarded — a header the app has never
                    // heard of is exactly how a bank goes missing without anyone noticing.
                    is SmsParser.Result.Unrecognised -> pendingSenders.record(result, date)
                    else -> Unit // ignored + reminders are not expenses
                }

                scanned++
                if (scanned % 25 == 0) {
                    setProgress(
                        workDataOf(
                            KEY_SCANNED to scanned,
                            KEY_TOTAL to total,
                            KEY_IMPORTED to imported,
                        )
                    )
                }
            }
        }

        prefs.setBackfillDone(true)
        return Result.success(
            workDataOf(KEY_SCANNED to scanned, KEY_IMPORTED to imported, KEY_TOTAL to scanned)
        )
    }

    private fun foregroundInfo(): androidx.work.ForegroundInfo {
        val notification = androidx.core.app.NotificationCompat.Builder(
            context, com.manuel.ours.OursApp.CHANNEL_SYNC,
        )
            .setSmallIcon(com.manuel.ours.R.drawable.ic_notification)
            .setContentTitle("Reading your messages")
            .setContentText("Importing expenses from the last 6 months")
            .setOngoing(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .build()

        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            androidx.work.ForegroundInfo(
                FOREGROUND_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            androidx.work.ForegroundInfo(FOREGROUND_ID, notification)
        }
    }

    private fun workDataOf(vararg pairs: Pair<String, Any?>): Data =
        Data.Builder().apply {
            pairs.forEach { (k, v) ->
                when (v) {
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is String -> putString(k, v)
                    is Boolean -> putBoolean(k, v)
                }
            }
        }.build()

    companion object {
        const val WORK_NAME = "sms_backfill"
        const val KEY_MONTHS = "months"
        const val KEY_SCANNED = "scanned"
        const val KEY_TOTAL = "total"
        const val KEY_IMPORTED = "imported"
        const val KEY_ERROR = "error"
        const val DEFAULT_MONTHS = 6
        private const val FOREGROUND_ID = 7711

        fun start(context: Context, months: Int = DEFAULT_MONTHS) {
            enqueue(context, months, ExistingWorkPolicy.KEEP)
        }

        /**
         * Re-reads the inbox from scratch. REPLACE rather than KEEP, because the
         * first-run job has already completed and KEEP would silently do nothing —
         * which is exactly what happens if you skip the permission during onboarding
         * and grant it later.
         *
         * Safe to run repeatedly: [com.manuel.ours.data.sms.SmsDeduplicator] keys
         * every message, so re-scanning imports only what is genuinely new.
         */
        fun rescan(context: Context, months: Int = DEFAULT_MONTHS) {
            enqueue(context, months, ExistingWorkPolicy.REPLACE)
        }

        private fun enqueue(context: Context, months: Int, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<SmsBackfillWorker>()
                .setInputData(Data.Builder().putInt(KEY_MONTHS, months).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }

        fun observeProgress(context: Context): Flow<BackfillProgress?> =
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(WORK_NAME)
                .map { infos ->
                    val info = infos.firstOrNull() ?: return@map null
                    val data = if (info.state == WorkInfo.State.SUCCEEDED) {
                        info.outputData
                    } else {
                        info.progress
                    }
                    BackfillProgress(
                        scanned = data.getInt(KEY_SCANNED, 0),
                        total = data.getInt(KEY_TOTAL, 0),
                        imported = data.getInt(KEY_IMPORTED, 0),
                        finished = info.state.isFinished,
                    )
                }
    }

    data class BackfillProgress(
        val scanned: Int,
        val total: Int,
        val imported: Int,
        val finished: Boolean,
    ) {
        val fraction: Float get() = if (total <= 0) 0f else (scanned.toFloat() / total).coerceIn(0f, 1f)
    }
}
