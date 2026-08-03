package com.manuel.ours.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.manuel.ours.data.sync.NearbyTransport
import com.manuel.ours.data.sync.SafFolderTransport
import com.manuel.ours.data.sync.SheetTransport
import com.manuel.ours.data.sync.SyncEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Syncs the household over Bluetooth, and only Bluetooth.
 *
 * There is deliberately no cloud path: nothing about your spending ever leaves the
 * two phones. The trade is real and worth stating — expenses recorded on one phone
 * appear on the other only when they are physically near each other, not instantly
 * from across town.
 *
 * Note the absence of a network constraint. This job needs no internet at all, and
 * requiring one would stop it running in exactly the situation it is built for: two
 * phones together, offline.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val engine: SyncEngine,
    private val nearbyTransport: NearbyTransport,
    private val folderTransport: SafFolderTransport,
    private val sheetTransport: SheetTransport,
) : CoroutineWorker(context, params) {

    /**
     * Tries both paths each round: the shared folder (works from anywhere, if one is
     * chosen) and Bluetooth (works with no setup, if the other phone is close).
     *
     * Neither being usable is the normal case, not a failure — retrying would burn
     * battery scanning for a phone that simply is not there.
     */
    override suspend fun doWork(): Result {
        var attempted = 0
        var succeeded = 0

        // Sheet first: it is the one path that works when the phones are apart and
        // no folder has been shared.
        if (sheetTransport.isAvailable()) {
            attempted++
            if (engine.sync(sheetTransport).success) succeeded++
        }

        if (folderTransport.isAvailable()) {
            attempted++
            if (engine.sync(folderTransport).success) succeeded++
        }

        if (nearbyTransport.isAvailable()) {
            attempted++
            if (engine.sync(nearbyTransport).success) succeeded++
        }

        engine.compactOwnLog()

        return when {
            attempted == 0 -> Result.success()
            succeeded > 0 -> Result.success()
            else -> Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "sync_periodic"
        private const val ONE_SHOT = "sync_now"

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.SECONDS,
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
