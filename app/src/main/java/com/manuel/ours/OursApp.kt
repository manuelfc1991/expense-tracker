package com.manuel.ours

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.data.sync.LamportClock
import com.manuel.ours.work.SmsBackfillWorker
import com.manuel.ours.data.sync.NearbySyncService
import com.manuel.ours.work.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OursApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var clock: LamportClock
    @Inject lateinit var repository: TransactionRepository
    @Inject lateinit var householdRepository: com.manuel.ours.data.repo.HouseholdRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        scope.launch {
            // The clock must be restored before anything mints an event, or a restart
            // would emit lamport 1 again and lose to every existing row.
            clock.observe(prefs.readLamport())
            repository.seedMerchantRulesIfNeeded()
            repository.adoptLocalTransactions()
            // One-shot repair for credits imported before bare credits carried their
            // bank's name. A rescan cannot fix these — dedup recognises them and returns
            // before the merchant is reconsidered.
            repository.relabelBareCredits()
            // Backfill the synchronous mirror for anyone who onboarded before it
            // existed, so they don't get sent back through onboarding once.
            if (prefs.onboarded.first() && !prefs.onboardedBlocking()) {
                prefs.setOnboarded(true)
            }
            // Bring a pre-derivation household onto the derived id, so a partner
            // joining by typed code lands in the same place as one joining by QR.
            householdRepository.migrateHouseholdIdIfLegacy()

            SyncWorker.enqueuePeriodic(this@OursApp)

            // Restore nearby sync if it is switched on. The toggle used to be the only
            // thing that ever started the service, so after an app restart — or a phone
            // reboot — the switch still read ON while nothing was advertising or
            // scanning. The setting claimed a capability that was not running, which is
            // indistinguishable from "your partner is not nearby".
            if (prefs.nearbyAlways.first()) {
                NearbySyncService.start(this@OursApp)
            }

            // Safety net for the "skipped the permission, granted it later" path.
            // Without this the app sits permanently empty even though it now has
            // access, because the backfill is only ever kicked off from onboarding.
            val canReadSms = ContextCompat.checkSelfPermission(
                this@OursApp, Manifest.permission.READ_SMS,
            ) == PackageManager.PERMISSION_GRANTED

            if (canReadSms && !prefs.backfillDone.first()) {
                SmsBackfillWorker.start(this@OursApp)
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EXPENSES,
                "New expenses",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "A transaction was detected in your SMS" }
        )

        // IMPORTANCE_HIGH is what makes the quick-categorize prompt appear as a
        // heads-up banner instead of a silent row in the shade — the category chips
        // are useless if you have to pull the tray down to find them.
        //
        // A channel's importance is fixed once created, so this is a second channel
        // rather than an edit to the one above. The user can still turn it down in
        // system settings, and that choice sticks.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EXPENSES_ACTIONABLE,
                "New expenses (quick categorize)",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description =
                    "Pops up as a transaction happens so you can categorise it in one tap"
                enableVibration(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_BUDGET,
                "Budget alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Warnings as you approach a budget limit" }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                "Sync",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Background sync status" }
        )
    }

    companion object {
        const val CHANNEL_EXPENSES = "expenses"
        const val CHANNEL_EXPENSES_ACTIONABLE = "expenses_actionable"
        const val CHANNEL_BUDGET = "budget"
        const val CHANNEL_SYNC = "sync"
    }
}
