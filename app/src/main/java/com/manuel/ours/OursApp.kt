package com.manuel.ours

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.room.InvalidationTracker
import androidx.work.Configuration
import com.manuel.ours.data.db.AppDatabase
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.repo.TransactionRepository
import com.manuel.ours.data.sync.LamportClock
import com.manuel.ours.data.sync.NearbySyncService
import com.manuel.ours.widget.SpendWidgetProvider
import com.manuel.ours.work.SmsBackfillWorker
import com.manuel.ours.work.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class OursApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var prefs: AppPrefs
    @Inject lateinit var clock: LamportClock
    @Inject lateinit var repository: TransactionRepository
    @Inject lateinit var householdRepository: com.manuel.ours.data.repo.HouseholdRepository
    @Inject lateinit var database: AppDatabase
    @Inject lateinit var rulesRepository: com.manuel.ours.data.repo.RulesRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Coalesces widget redraws. A six-month backfill writes hundreds of rows and would
     * otherwise fire a broadcast for each burst; only the final figure is worth drawing.
     */
    private val widgetRefreshes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        keepWidgetInStep()

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
            // Same shape of one-shot: rows whose merchant is an account label the
            // parser mistook for a payee, which a rescan cannot reach because dedup
            // returns before the merchant is reconsidered.
            repository.repairAccountLabelMerchants()
            // Folds an earlier install's identity back onto this person, so a reinstall
            // does not leave a second copy of them in the household filter.
            repository.mergeOwnAliases()
            // Removes the duplicates that fixing AM/PM created on inboxes already
            // holding wrongly-timed evening rows.
            repository.repairMeridiemTwins()
            // One bill, two banks, two messages — the card's acknowledgement echoes a
            // debit the app already has.
            repository.repairCardBillEchoes()
            // Not a one-shot: an accidental round trip can happen any week, and the
            // pass costs nothing when there is no pair to find.
            // Read the destination account out of messages stored before the column
            // existed, so naming an account fixes the history it came from.
            repository.backfillCounterpartyTails()
            repository.markSelfTransfers()
            // Sender and category rules taught through the sheet, applied before the
            // first message of this launch is parsed.
            rulesRepository.apply()
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

    /**
     * Redraw the home-screen widget whenever the numbers behind it move.
     *
     * The widget had no trigger at all: `updatePeriodMillis` is 30 minutes, the system
     * treats that as a hint, and nothing else ever asked for a redraw. So it showed a
     * total that could be half an hour stale and never budged in the moment you would
     * actually look — just after paying for something.
     *
     * Watching Room's invalidation tracker rather than calling from each write site is
     * deliberate. Rows arrive from SMS, from the notification listener, from a sync
     * merge, from manual entry and from a recategorize; hanging a refresh off each of
     * those is five chances to forget one, and the sixth path added later would be
     * silently stale. The table is the one thing they all have in common.
     *
     * `budgets` is watched too because the subtitle reads "68% of ₹32K" — changing the
     * limit changes the widget without any transaction moving.
     */
    private fun keepWidgetInStep() {
        scope.launch {
            widgetRefreshes
                .debounce(1_500)
                .collect { SpendWidgetProvider.refresh(this@OursApp) }
        }
        database.invalidationTracker.addObserver(
            object : InvalidationTracker.Observer("transactions", "budgets") {
                override fun onInvalidated(tables: Set<String>) {
                    widgetRefreshes.tryEmit(Unit)
                }
            }
        )
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
