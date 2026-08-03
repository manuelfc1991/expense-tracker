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
import com.manuel.ours.data.repo.HouseholdRepository
import com.manuel.ours.data.sync.NearbySyncService
import com.manuel.ours.domain.model.Member
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val syncFolderName: String? = null,
    val sheetUrl: String = "",
    val sheetStatus: String? = null,
    val sheetTesting: Boolean = false,
    val appLock: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val ingestSource: IngestSource = IngestSource.SMS,
    /** Epoch millis before which nothing is counted; 0 means the whole history. */
    val trackingStartAt: Long = 0L,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val prefs: AppPrefs,
    private val householdRepository: HouseholdRepository,
    private val sheetTransport: com.manuel.ours.data.sync.SheetTransport,
    private val syncEventDao: com.manuel.ours.data.db.SyncEventDao,
) : AndroidViewModel(application) {

    private val _sheetStatus = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val sheetStatus: kotlinx.coroutines.flow.StateFlow<String?> = _sheetStatus

    private val _sheetTesting = kotlinx.coroutines.flow.MutableStateFlow(false)
    val sheetTesting: kotlinx.coroutines.flow.StateFlow<Boolean> = _sheetTesting

    val uiState: StateFlow<SettingsUiState> = combine(
        householdRepository.observeMembers(),
        prefs.inviteSecret,
        prefs.lastSyncAt,
        prefs.nearbyAlways,
        combine(
            prefs.appLock, prefs.theme, prefs.ingestSource, prefs.householdId,
            combine(prefs.syncFolderUri, prefs.sheetUrl, prefs.trackingStartAt) {
                folder, sheet, start -> Triple(folder, sheet, start)
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
        val paths = rest[4] as Triple<String?, String?, Long>
        val folderUri = paths.first
        val sheet = paths.second.orEmpty()
        val trackingStartAt = paths.third

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
            syncFolderName = folderUri?.let(::prettyFolderName),
            sheetUrl = sheet,
            sheetStatus = sheetState,
            appLock = appLock,
            theme = theme,
            ingestSource = source,
            trackingStartAt = trackingStartAt,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState(),
    )

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
    fun setTrackingStartAt(epochMillis: Long) {
        viewModelScope.launch { prefs.setTrackingStartAt(epochMillis) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { prefs.setTheme(mode) }
    }

    fun setAppLock(value: Boolean) {
        viewModelScope.launch { prefs.setAppLock(value) }
    }

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
    fun setSyncFolder(uri: android.net.Uri?) {
        viewModelScope.launch {
            if (uri == null) {
                prefs.setSyncFolderUri(null)
                return@launch
            }
            runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            prefs.setSyncFolderUri(uri.toString())
            SyncWorker.syncNow(getApplication())
        }
    }

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
                    if (changed) syncEventDao.markAllUnpushed()
                    _sheetStatus.value = "Connected to \"$it\""
                    SyncWorker.syncNow(getApplication())
                }
                .onFailure { _sheetStatus.value = it.message ?: "Could not reach the sheet" }

            _sheetTesting.value = false
        }
    }

    /** Turns an opaque tree URI into something a human recognises. */
    private fun prettyFolderName(uriString: String): String = runCatching {
        val uri = android.net.Uri.parse(uriString)
        android.provider.DocumentsContract.getTreeDocumentId(uri)
            .substringAfterLast(':')
            .substringAfterLast('/')
            .ifBlank { "Selected folder" }
    }.getOrDefault("Selected folder")

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
}
