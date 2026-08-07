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
import androidx.work.workDataOf
import com.manuel.ours.data.sync.SyncOutcome
import com.manuel.ours.data.sync.NearbyTransport
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
    private val sheetTransport: SheetTransport,
    private val rulesRepository: com.manuel.ours.data.repo.RulesRepository,
    private val prefs: com.manuel.ours.data.prefs.AppPrefs,
) : CoroutineWorker(context, params) {

    /**
     * Tries both paths each round: the sheet (works from anywhere, if one is
     * configured) and Bluetooth (works with no setup, if the other phone is close).
     *
     * Neither being usable is the normal case, not a failure — retrying would burn
     * battery scanning for a phone that simply is not there.
     */
    override suspend fun doWork(): Result {
        var attempted = 0
        var succeeded = 0
        var pushed = 0
        var pulled = 0
        val used = mutableListOf<String>()
        // A transport that threw is the single most useful thing to report, and the
        // one thing this worker used to discard. Without it a push that fails every
        // time is indistinguishable from having nothing to send.
        val failures = mutableListOf<String>()

        // Sheet first: it is the one path that works when the phones are apart and
        // no folder has been shared.
        if (sheetTransport.isAvailable()) {
            attempted++
            val outcome = engine.sync(sheetTransport)
            if (!outcome.success) failures += describe(outcome)
            if (outcome.success) {
                succeeded++
                pushed += outcome.pushedEvents
                pulled += outcome.appliedEvents
                if (outcome.pushedEvents > 0 || outcome.appliedEvents > 0) used += outcome.transport
            }
        }


        // Rules ride along with the sheet, after the ledger has moved. Failure here is
        // swallowed inside the repository: the ledger is the job, rules are a bonus.
        if (sheetTransport.isAvailable()) rulesRepository.sync(sheetTransport)

        if (nearbyTransport.isAvailable()) {
            attempted++
            val outcome = engine.sync(nearbyTransport)
            if (!outcome.success) failures += describe(outcome)
            if (outcome.success) {
                succeeded++
                pushed += outcome.pushedEvents
                pulled += outcome.appliedEvents
                if (outcome.pushedEvents > 0 || outcome.appliedEvents > 0) used += outcome.transport
            }
        }

        engine.compactOwnLog()

        // Report what actually moved. Without this the UI can only say "it ran",
        // which is indistinguishable from "it ran and did nothing because your sheet
        // was unreachable" — the exact confusion this app has already caused once.
        val summary = workDataOf(
            KEY_ATTEMPTED to attempted,
            KEY_PUSHED to pushed,
            KEY_PULLED to pulled,
            KEY_TRANSPORTS to used.distinct().joinToString(", "),
            KEY_ERROR to failures.joinToString(" · "),
        )

        // Record the outcome where the interface can see it. `setLastSync` already clears the
        // error on success; this is the other half, so a failure survives to be shown on Home
        // rather than only appearing in a Settings disclosure nobody opens.
        if (attempted > 0 && succeeded == 0) {
            prefs.setSyncError(failures.joinToString(" · ").ifBlank { "Could not reach anything to sync with" })
        }

        return when {
            attempted == 0 -> Result.success(summary)
            succeeded > 0 -> Result.success(summary)
            else -> Result.retry()
        }
    }

    /** Transport name plus whatever the failure actually was. */
    private fun describe(outcome: SyncOutcome): String {
        val reason = outcome.error?.message?.takeIf { it.isNotBlank() }
            ?: outcome.error?.let { it::class.simpleName }
            ?: "unknown error"
        return "${outcome.transport}: $reason"
    }

    companion object {
        private const val PERIODIC = "sync_periodic"

        /** Public so the UI can observe this run and report what it moved. */
        const val ONE_SHOT = "sync_now"

        const val KEY_ATTEMPTED = "attempted"
        const val KEY_PUSHED = "pushed"
        const val KEY_PULLED = "pulled"
        const val KEY_TRANSPORTS = "transports"
        const val KEY_ERROR = "error"

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
