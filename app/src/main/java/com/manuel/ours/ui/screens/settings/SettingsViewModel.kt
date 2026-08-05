package com.manuel.ours.ui.screens.settings

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.content.ContextCompat
import com.manuel.ours.work.SmsBackfillWorker
import com.manuel.ours.work.SyncWorker
import kotlinx.coroutines.flow.Flow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.manuel.ours.data.prefs.AppPrefs
import com.manuel.ours.data.prefs.IngestSource
import com.manuel.ours.data.prefs.ThemeMode
import com.manuel.ours.ui.theme.ThemeTone
import com.manuel.ours.ui.theme.AccentColor
import com.manuel.ours.data.repo.HouseholdRepository
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.manuel.ours.data.sync.SheetTransport
import com.manuel.ours.data.sync.NearbySyncService
import com.manuel.ours.domain.model.Member
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val hasSmsPermission: Boolean = false,
    val scanning: Boolean = false,
    val scanScanned: Int = 0,
    val scanTotal: Int = 0,
    val scanImported: Int = 0,
    val members: List<Member> = emptyList(),
    val inviteSecret: String? = null,
    val inviteQr: Bitmap? = null,
    val lastSyncLabel: String = "Never synced",
    val nearbyAlways: Boolean = false,
    val sheetUrl: String = "",
    val sheetStatus: String? = null,
    val sheetTesting: Boolean = false,
    val appLock: Boolean = false,
    val capturePopup: Boolean = false,
    val settingsIndex: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val tone: ThemeTone = ThemeTone.CRISP,
    val accent: AccentColor = AccentColor.BLUE,
    val ingestSource: IngestSource = IngestSource.SMS,
    /** Epoch millis before which nothing is counted; 0 means the whole history. */
    val trackingStartAt: Long = 0L,
    val isHouseholdOwner: Boolean = false,
    val pendingDeleteRequests: Int = 0,
    val developerMode: Boolean = false,
    val transactionCount: Int = 0,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val prefs: AppPrefs,
    private val householdRepository: HouseholdRepository,
    private val sheetTransport: com.manuel.ours.data.sync.SheetTransport,
    private val syncEventDao: com.manuel.ours.data.db.SyncEventDao,
    private val transactionRepository: com.manuel.ours.data.repo.TransactionRepository,
    private val updateChecker: com.manuel.ours.data.update.UpdateChecker,
    private val ingestNotifier: com.manuel.ours.data.sms.IngestNotifier,
) : AndroidViewModel(application) {

    /**
     * Fires the real expense notification against a made-up transaction.
     *
     * The heads-up prompt is the surface the household sees most often and the only one
     * that had never been looked at: Android refuses to let anything but the system
     * broadcast an incoming SMS, so on a real phone there was no way to see it without
     * waiting for the bank to send something. That made every change to the layout an
     * unverified change.
     *
     * It calls [IngestNotifier.notifyExpense] rather than building a notification of its
     * own, so what appears is exactly what a real payment produces — including the
     * category buttons, which are the part most likely to break.
     *
     * Nothing is written to the database. The transaction exists only for the length of
     * this call; its id is fixed so repeated taps replace the notification instead of
     * stacking up, and it can never collide with a real row.
     */
    fun sendTestNotification() {
        val now = System.currentTimeMillis()
        ingestNotifier.notifyExpense(
            txn = com.manuel.ours.domain.model.Transaction(
                id = TEST_NOTIFICATION_ID,
                amountPaise = 15_100,
                type = com.manuel.ours.domain.model.TxnType.DEBIT,
                merchant = "Keecheril St",
                category = com.manuel.ours.domain.model.Category.FOOD,
                occurredAt = now,
                bank = "Federal Bank",
                ownerUid = "test",
                ownerName = "Me",
                needsReview = true,
            ),
            // Three, because three is all Android will draw. Hard-coded rather than
            // predicted: a test whose output depends on what the app has learned cannot
            // tell you whether the notification is right.
            suggestions = listOf(
                com.manuel.ours.domain.model.Category.FOOD,
                com.manuel.ours.domain.model.Category.GROCERIES,
                com.manuel.ours.domain.model.Category.TRANSPORT,
            ),
        )
    }

    /**
     * Fires the real capture popup, after long enough to leave the app.
     *
     * The pause is the test. The popup deliberately does nothing while Ours is in front —
     * the in-app sheet covers that case — so a version that appeared instantly would only
     * prove the parts that were never in doubt. Three seconds is enough to press Home,
     * which puts the app in exactly the state a real payment finds it in.
     *
     * It uses the newest real transaction rather than a made-up one, because the popup
     * reads the row back from the database and would have nothing to draw otherwise.
     * Nothing is written; the popup can only change a row you already have.
     */
    fun sendTestPopup() {
        viewModelScope.launch {
            val newest = transactionRepository.observeAll().first()
                .maxByOrNull { it.occurredAt } ?: return@launch
            kotlinx.coroutines.delay(3_000)
            ingestNotifier.popUp(newest)
        }
    }

    private val _update =
        kotlinx.coroutines.flow.MutableStateFlow<com.manuel.ours.data.update.UpdateChecker.Result?>(null)
    val update: kotlinx.coroutines.flow.StateFlow<com.manuel.ours.data.update.UpdateChecker.Result?> = _update

    private val _updateStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val updateStatus: kotlinx.coroutines.flow.StateFlow<String?> = _updateStatus

    /** True while a check or a download is in flight, so the UI can show it working. */
    private val _updateBusy = kotlinx.coroutines.flow.MutableStateFlow(false)
    val updateBusy: kotlinx.coroutines.flow.StateFlow<Boolean> = _updateBusy

    private val _updateFile = kotlinx.coroutines.flow.MutableStateFlow<java.io.File?>(null)
    val updateFile: kotlinx.coroutines.flow.StateFlow<java.io.File?> = _updateFile

    fun checkForUpdate() {
        viewModelScope.launch {
            _updateBusy.value = true
            _updateStatus.value = "Checking…"
            val result = updateChecker.check()
            _update.value = result
            _updateBusy.value = false
            _updateStatus.value = when (result) {
                is com.manuel.ours.data.update.UpdateChecker.Result.UpToDate ->
                    "You are on the latest build"
                is com.manuel.ours.data.update.UpdateChecker.Result.Update ->
                    "Version ${result.available.versionName} is available"
                is com.manuel.ours.data.update.UpdateChecker.Result.Failed ->
                    "Could not check — ${result.reason}"
            }
        }
    }

    fun downloadUpdate() {
        val available = (_update.value as? com.manuel.ours.data.update.UpdateChecker.Result.Update)
            ?.available ?: return
        viewModelScope.launch {
            _updateBusy.value = true
            _updateStatus.value = "Downloading…"
            updateChecker.download(available)
                .onSuccess {
                    _updateFile.value = it
                    _updateStatus.value = "Ready to install"
                }
                .onFailure { _updateStatus.value = it.message ?: "Download failed" }
            _updateBusy.value = false
        }
    }

    fun clearUpdateFile() { _updateFile.value = null }

    private val _sheetStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val sheetStatus: kotlinx.coroutines.flow.StateFlow<String?> = _sheetStatus

    private val _sheetTesting = kotlinx.coroutines.flow.MutableStateFlow(false)
    val sheetTesting: kotlinx.coroutines.flow.StateFlow<Boolean> = _sheetTesting

    /** Bundled so the outer combine stays within the five arities Flow offers. */
    private data class Flags(
        val developerMode: Boolean,
        val transactionCount: Int,
        val capturePopup: Boolean,
        val settingsIndex: Boolean,
        val tone: ThemeTone,
        val accent: AccentColor,
    )

    val uiState: StateFlow<SettingsUiState> = combine(
        householdRepository.observeMembers(),
        prefs.inviteSecret,
        prefs.lastSyncAt,
        prefs.nearbyAlways,
        combine(
            prefs.appLock, prefs.theme, prefs.ingestSource, prefs.householdId,
            combine(
                prefs.sheetUrl,
                prefs.trackingStartAt,
                prefs.householdOwner,
                transactionRepository.observeDeleteRequestCount(),
                combine(
                    prefs.developerMode,
                    transactionRepository.observeAll(),
                    prefs.capturePopup,
                    prefs.settingsIndex,
                    combine(prefs.themeTone, prefs.accentColor) { t, a -> t to a },
                ) { dev, all, popup, index, look ->
                    @Suppress("UNCHECKED_CAST")
                    val pair = look as Pair<ThemeTone, AccentColor>
                    Flags(
                        developerMode = dev as Boolean,
                        transactionCount = (all as List<*>).size,
                        capturePopup = popup as Boolean,
                        settingsIndex = index as Boolean,
                        tone = pair.first,
                        accent = pair.second,
                    )
                },
            ) { sheet, start, owner, pending, dev ->
                listOf(sheet, start, owner, pending, dev)
            },
        ) { lock, theme, source, household, paths ->
            listOf(lock, theme, source, household, paths)
        },
    ) { members, secret, lastSync, nearby, rest ->
        val appLock = rest[0] as Boolean
        val theme = rest[1] as ThemeMode
        val source = rest[2] as IngestSource
        val householdId = rest[3] as String?
        @Suppress("UNCHECKED_CAST")
        val paths = rest[4] as List<Any?>
        val sheet = (paths[0] as String?).orEmpty()
        val trackingStartAt = paths[1] as Long
        val isOwner = paths[2] as Boolean
        val pendingDeletes = paths[3] as Int
        @Suppress("UNCHECKED_CAST")
        val dev = paths[4] as Flags

        SettingsUiState(
            members = members,
            inviteSecret = secret,
            inviteQr = if (secret != null && householdId != null) {
                generateQr(
                    HouseholdRepository.InviteBundle(
                        householdId = householdId,
                        inviteSecret = secret,
                    ).encode()
                )
            } else null,
            lastSyncLabel = relativeSyncLabel(lastSync),
            nearbyAlways = nearby,
            sheetUrl = sheet,
            sheetStatus = sheetState,
            appLock = appLock,
            theme = theme,
            ingestSource = source,
            trackingStartAt = trackingStartAt,
            isHouseholdOwner = isOwner,
            pendingDeleteRequests = pendingDeletes,
            developerMode = dev.developerMode,
            transactionCount = dev.transactionCount,
            capturePopup = dev.capturePopup,
            settingsIndex = dev.settingsIndex,
            tone = dev.tone,
            accent = dev.accent,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

    /**
     * How many expenses the tracking cutoff is holding back from the sheet right now.
     *
     * Read before "Re-upload everything" is pressed rather than inferred from the count
     * afterwards. Kept out of [uiState] deliberately: that combine is already five deep
     * and nested three levels to get there, and one more flow through it would cost more
     * in unchecked casts than this whole disclosure is worth.
     */
    val retiredCount: StateFlow<Int> = transactionRepository.observeRetiredCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Live permission state — the user can revoke it in system settings at any time. */
    fun hasSmsPermission(): Boolean = ContextCompat.checkSelfPermission(
        getApplication(), Manifest.permission.READ_SMS,
    ) == PackageManager.PERMISSION_GRANTED

    val smsPermissions = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS,
    )

    /** Re-reads the inbox. Needed after granting permission late, or after a fix. */
    fun rescanMessages() {
        SmsBackfillWorker.rescan(getApplication())
    }

    fun observeScanProgress(): Flow<SmsBackfillWorker.BackfillProgress?> =
        SmsBackfillWorker.observeProgress(getApplication())

    /**
     * Sets the date before which nothing is counted, or 0 to show everything again.
     *
     * Deliberately not a delete. The rows stay in the database, so this is reversible
     * by moving the date back — and a household that retires a messy month should not
     * have to destroy it to do so.
     */
    /**
     * Queues this device's entire history for upload again.
     *
     * Connecting a *different* sheet re-queues automatically, but that is not the only
     * way a sheet ends up empty: recreating the spreadsheet behind the same deployment,
     * clearing its rows by hand, or a partner joining months late all leave the phone
     * believing everything was already sent. "Pushed" only ever meant "pushed to
     * whatever sheet was there at the time", and nothing on the phone can tell the
     * difference — so this is a button rather than a guess.
     *
     * Safe to press at any time: transports are required to be idempotent, so a repeat
     * push cannot duplicate rows.
     */
    /**
     * What the last "Sync now" actually did, as a sentence.
     *
     * Sync is the one action in this app with no visible result — the numbers on every
     * other screen come from the local database, so a sync that reached nothing looks
     * exactly like a sync that worked. This reports what moved, including "nothing",
     * which is a real and common answer rather than a failure.
     */
    val syncProgress: StateFlow<String?> =
        WorkManager.getInstance(application)
            .getWorkInfosForUniqueWorkFlow(SyncWorker.ONE_SHOT)
            .map { infos ->
                val info = infos.lastOrNull() ?: return@map null
                when (info.state) {
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> "Syncing…"
                    WorkInfo.State.SUCCEEDED -> {
                        val d = info.outputData
                        val attempted = d.getInt(SyncWorker.KEY_ATTEMPTED, 0)
                        val pushed = d.getInt(SyncWorker.KEY_PUSHED, 0)
                        val pulled = d.getInt(SyncWorker.KEY_PULLED, 0)
                        val via = d.getString(SyncWorker.KEY_TRANSPORTS).orEmpty()
                        val failure = d.getString(SyncWorker.KEY_ERROR).orEmpty()
                        when {
                            attempted == 0 -> "No sync set up yet"
                            // Before this, a transport that threw on every attempt
                            // reported "Already up to date" — the app claiming success
                            // for the exact failure the user was trying to diagnose.
                            failure.isNotBlank() && pushed == 0 && pulled == 0 ->
                                "Couldn't sync — $failure"
                            pushed == 0 && pulled == 0 -> "Already up to date"
                            else -> buildString {
                                if (pushed > 0) append("Sent $pushed")
                                if (pushed > 0 && pulled > 0) append(" · ")
                                if (pulled > 0) append("Received $pulled")
                                if (via.isNotBlank()) append(" · $via")
                            }
                        }
                    }
                    WorkInfo.State.FAILED -> "Sync failed"
                    // Retry means every transport it tried was unreachable.
                    WorkInfo.State.BLOCKED, WorkInfo.State.CANCELLED -> null
                    else -> "Could not reach anything to sync with"
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun reuploadEverything() {
        viewModelScope.launch {
            _sheetStatus.value = "Clearing the sheet…"
            // Clear first. The events tab is append-only, so rebuilding without this
            // stacks a second copy beside the first, and anything the phone has since
            // stopped syncing — a retired month — would linger in the ledger forever.
            when (val cleared = sheetTransport.reset()) {
                SheetTransport.ResetResult.Ok -> Unit

                // Name the fix. This is the one failure the user can actually resolve,
                // and it looks identical to a dead URL unless the message says so.
                SheetTransport.ResetResult.ScriptOutdated -> {
                    _sheetStatus.value =
                        "This sheet is running an old copy of the script. Open " +
                            "\"How do I set this up?\", copy the script into the " +
                            "sheet's Apps Script, then Deploy ▸ Manage deployments ▸ " +
                            "New version — editing the existing deployment keeps this URL."
                    return@launch
                }

                is SheetTransport.ResetResult.Failed -> {
                    _sheetStatus.value = "Could not clear the sheet" +
                        (cleared.reason?.let { ": $it" } ?: ". Check the link and try again.")
                    return@launch
                }
            }
            _sheetStatus.value = "Rebuilding…"
            val tally = transactionRepository.rebuildOwnLog()
            prefs.setSheetCursor(0L)
            // Say what stayed behind. The cutoff bounding a re-upload is deliberate, but
            // "Queued 214 expenses" for a household holding 460 of them read as success
            // and was half a story — and the half it left out is the one somebody would
            // go looking for on the other phone.
            val startAt = prefs.trackingStartAtOnce()
            _sheetStatus.value = tally.statusLine(
                cutoffLabel = if (startAt > 0L) formatDay(startAt) else null,
            )
            SyncWorker.syncNow(getApplication())
        }
    }

    /**
     * Off is always allowed; on is not.
     *
     * Editing an amount is an owner's decision, so the guard lives here as well as in
     * the repository — a capability protected only by which screen you can reach is not
     * protected at all.
     */
    /**
     * Declared, not detected.
     *
     * Nothing recorded who created a household made before the flag existed, and there
     * is no signal to recover it from — the invite secret and the derived id are held
     * by everyone. Turning it off also drops developer mode, so the two cannot drift
     * apart into an owner-only capability held by a member.
     */
    fun setHouseholdOwner(owner: Boolean) {
        viewModelScope.launch {
            prefs.setHouseholdOwner(owner)
            if (!owner) prefs.setDeveloperMode(false)
        }
    }

    fun setDeveloperMode(on: Boolean) {
        viewModelScope.launch {
            if (on && !prefs.householdOwnerOnce()) return@launch
            prefs.setDeveloperMode(on)
        }
    }

    fun setTrackingStartAt(epochMillis: Long) {
        viewModelScope.launch { prefs.setTrackingStartAt(epochMillis) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { prefs.setTheme(mode) }
    }

    fun setAppLock(value: Boolean) {
        viewModelScope.launch { prefs.setAppLock(value) }
    }

    fun setCapturePopup(value: Boolean) {
        viewModelScope.launch { prefs.setCapturePopup(value) }
    }

    fun setThemeTone(value: ThemeTone) {
        viewModelScope.launch { prefs.setThemeTone(value) }
    }

    fun setAccentColor(value: AccentColor) {
        viewModelScope.launch { prefs.setAccentColor(value) }
    }

    fun setSettingsIndex(value: Boolean) {
        viewModelScope.launch { prefs.setSettingsIndex(value) }
    }

    /**
     * Whether Android currently lets the popup appear.
     *
     * Read on every recomposition rather than stored, because it changes in system
     * settings — outside this app entirely — and nothing tells us when it does.
     */
    fun canPopUp(): Boolean =
        com.manuel.ours.ui.capture.CaptureActivity.permitted(getApplication())

    fun setIngestSource(source: IngestSource) {
        viewModelScope.launch { prefs.setIngestSource(source) }
    }

    /** Starting/stopping the foreground service here keeps the toggle honest. */
    fun setNearbyAlways(value: Boolean) {
        viewModelScope.launch {
            prefs.setNearbyAlways(value)
            val context = getApplication<Application>()
            if (value) NearbySyncService.start(context) else NearbySyncService.stop(context)
        }
    }

    /**
     * Persists the folder grant so it survives reboots, and remembers the tree URI.
     * Without takePersistableUriPermission the grant dies with the process and sync
     * silently stops working after the next restart.
     */

    /**
     * Joins the household encoded in a scanned QR.
     *
     * Silently ignores an unparseable payload: the scanner fires on whatever QR
     * happens to be in frame, and a Wi-Fi or payment code must not wipe the user's
     * existing household.
     */
    fun joinFromScannedInvite(payload: String) {
        val bundle = HouseholdRepository.InviteBundle.decode(payload) ?: return
        viewModelScope.launch {
            val snapshot = prefs.snapshot()
            householdRepository.joinHousehold(
                bundle = bundle,
                uid = snapshot.selfUid ?: java.util.UUID.randomUUID().toString(),
                name = snapshot.selfName ?: "Me",
                email = snapshot.selfEmail.orEmpty(),
            )
            SyncWorker.syncNow(getApplication())
        }
    }

    private val sheetState = null

    /**
     * Saves the pasted Apps Script URL after checking it actually answers.
     *
     * Verifying before saving matters: a wrong or undeployed URL fails silently
     * during background sync, and the user would have no way to tell the difference
     * between "nothing to sync" and "the address is wrong".
     */
    fun saveSheetUrl(url: String) {
        viewModelScope.launch {
            _sheetTesting.value = true
            _sheetStatus.value = null
            val trimmed = url.trim()

            if (trimmed.isBlank()) {
                prefs.setSheetUrl(null)
                _sheetStatus.value = "Sheet sync turned off"
                _sheetTesting.value = false
                return@launch
            }

            sheetTransport.testConnection(trimmed)
                .onSuccess {
                    val changed = prefs.setSheetUrl(trimmed)
                    // A new spreadsheet holds none of this device's history, and
                    // "pushed" only ever meant "pushed to the previous sheet". Without
                    // this, pointing at a fresh sheet uploads nothing and the ledger
                    // starts from whatever happens next.
                    //
                    // Rebuild rather than merely un-mark: the existing log can predate
                    // the tracking cutoff, or a compaction pass, so re-queueing it
                    // as-is ships months the household has retired. Rebuilding from the
                    // transactions table produces exactly what is in scope today. The
                    // remote sheet is left alone — it may already hold a partner's rows.
                    if (changed) transactionRepository.rebuildOwnLog()
                    _sheetStatus.value = "Connected to \"$it\""
                    SyncWorker.syncNow(getApplication())
                }
                .onFailure { _sheetStatus.value = it.message ?: "Could not reach the sheet" }

            _sheetTesting.value = false
        }
    }

    /** Turns an opaque tree URI into something a human recognises. */

    /** Cached: the invite payload only changes when the household does. */
    private var cachedQr: Pair<String, Bitmap>? = null

    /**
     * Renders the invite QR.
     *
     * The old version called [Bitmap.setPixel] 262,144 times — a quarter of a million
     * JNI round-trips — inside the state `combine`, so it ran on the main thread every
     * time any setting changed. This builds one int array and hands it over in a
     * single call, and caches the result against its payload.
     */
    private fun generateQr(content: String, size: Int = 512): Bitmap? {
        cachedQr?.let { (key, bitmap) -> if (key == content) return bitmap }

        return runCatching {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val row = y * size
                for (x in 0 until size) {
                    pixels[row + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
                .also { cachedQr = content to it }
        }.getOrNull()
    }

    private companion object {
        /**
         * Fixed, and not a UUID, for two reasons: repeated taps replace the notification
         * rather than stacking a dozen of them, and no real transaction can ever carry
         * this id, so the category buttons find nothing to change.
         */
        const val TEST_NOTIFICATION_ID = "test-notification"
    }
}
