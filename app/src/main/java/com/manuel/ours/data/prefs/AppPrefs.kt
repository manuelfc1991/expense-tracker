package com.manuel.ours.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "ours_prefs")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class IngestSource { SMS, NOTIFICATION, MANUAL_ONLY }

@Singleton
class AppPrefs @Inject constructor(
    private val context: Context,
) {
    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val HOUSEHOLD_ID = stringPreferencesKey("household_id")
        val INVITE_SECRET = stringPreferencesKey("invite_secret")
        val SELF_UID = stringPreferencesKey("self_uid")
        val SELF_NAME = stringPreferencesKey("self_name")
        val SELF_EMAIL = stringPreferencesKey("self_email")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val LAMPORT = longPreferencesKey("lamport")
        val LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val LAST_SYNC_TRANSPORT = stringPreferencesKey("last_sync_transport")
        val THEME = stringPreferencesKey("theme")
        val NEARBY_ALWAYS = booleanPreferencesKey("nearby_always")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val INGEST_SOURCE = stringPreferencesKey("ingest_source")
        val BACKFILL_DONE = booleanPreferencesKey("backfill_done")
        val SEEDED = booleanPreferencesKey("seeded")
        val SYNC_FOLDER_URI = stringPreferencesKey("sync_folder_uri")
        val SHEET_URL = stringPreferencesKey("sheet_url")
        val SHEET_CURSOR = longPreferencesKey("sheet_cursor")
        val TRACKING_START_AT = longPreferencesKey("tracking_start_at")
        val FIRED_BUDGET_ALERTS = stringSetPreferencesKey("fired_budget_alerts")
    }

    /** Stable per-install identity. Doubles as the log filename and the merge tiebreak. */
    suspend fun deviceId(): String {
        val existing = context.dataStore.data.map { it[Keys.DEVICE_ID] }.first()
        if (existing != null) return existing
        val fresh = UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.DEVICE_ID] = fresh }
        return fresh
    }

    val householdId: Flow<String?> = context.dataStore.data.map { it[Keys.HOUSEHOLD_ID] }
    val inviteSecret: Flow<String?> = context.dataStore.data.map { it[Keys.INVITE_SECRET] }
    val selfUid: Flow<String?> = context.dataStore.data.map { it[Keys.SELF_UID] }
    val selfName: Flow<String?> = context.dataStore.data.map { it[Keys.SELF_NAME] }
    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDED] ?: false }

    val lastSyncAt: Flow<Long> = context.dataStore.data.map { it[Keys.LAST_SYNC_AT] ?: 0L }
    val lastSyncTransport: Flow<String?> =
        context.dataStore.data.map { it[Keys.LAST_SYNC_TRANSPORT] }
    val nearbyAlways: Flow<Boolean> = context.dataStore.data.map { it[Keys.NEARBY_ALWAYS] ?: false }
    val appLock: Flow<Boolean> = context.dataStore.data.map { it[Keys.APP_LOCK] ?: false }
    val backfillDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.BACKFILL_DONE] ?: false }

    /**
     * Epoch millis before which nothing is counted, or 0 for "count everything".
     *
     * This is a *view and import* boundary, not a delete: older rows stay in the
     * database untouched, so moving the date back brings the history straight back.
     * Nothing is destroyed to make a month look tidy.
     */
    val trackingStartAt: Flow<Long> =
        context.dataStore.data.map { it[Keys.TRACKING_START_AT] ?: 0L }

    suspend fun setTrackingStartAt(epochMillis: Long) {
        context.dataStore.edit { it[Keys.TRACKING_START_AT] = epochMillis.coerceAtLeast(0L) }
    }

    /** For the backfill worker, which runs outside a composition. */
    suspend fun trackingStartAtOnce(): Long = trackingStartAt.first()

    /** Tree URI of the user-picked sync folder, or null when none is chosen. */
    val syncFolderUri: Flow<String?> = context.dataStore.data.map { it[Keys.SYNC_FOLDER_URI] }

    /** Apps Script web-app URL both phones paste in. Blank means sheet sync is off. */
    val sheetUrl: Flow<String?> = context.dataStore.data.map { it[Keys.SHEET_URL] }

    suspend fun sheetUrlString(): String? =
        context.dataStore.data.map { it[Keys.SHEET_URL] }.first()?.takeIf { it.isNotBlank() }

    suspend fun setSheetUrl(url: String?) {
        context.dataStore.edit {
            if (url.isNullOrBlank()) {
                it.remove(Keys.SHEET_URL)
                // Reset the cursor too: a different sheet has different row numbers,
                // and carrying the old one over would skip real events.
                it.remove(Keys.SHEET_CURSOR)
            } else {
                it[Keys.SHEET_URL] = url.trim()
            }
        }
    }

    /** Last sheet row read. Row index, not a timestamp — rows never reorder. */
    suspend fun sheetCursor(): Long =
        context.dataStore.data.map { it[Keys.SHEET_CURSOR] ?: 0L }.first()

    suspend fun setSheetCursor(value: Long) {
        context.dataStore.edit { it[Keys.SHEET_CURSOR] = value }
    }

    suspend fun syncFolderUriString(): String? =
        context.dataStore.data.map { it[Keys.SYNC_FOLDER_URI] }.first()

    /**
     * Budget alerts already shown, keyed by month so the set self-prunes when the
     * month changes. Capped, because an unbounded preference set grows forever and
     * DataStore rewrites the whole file on every edit.
     */
    suspend fun hasBudgetAlertFired(key: String): Boolean =
        context.dataStore.data.map { it[Keys.FIRED_BUDGET_ALERTS].orEmpty() }.first()
            .contains(key)

    suspend fun markBudgetAlertFired(key: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.FIRED_BUDGET_ALERTS].orEmpty()
            val trimmed = if (current.size >= MAX_FIRED_ALERTS) {
                current.sorted().takeLast(MAX_FIRED_ALERTS / 2).toSet()
            } else current
            prefs[Keys.FIRED_BUDGET_ALERTS] = trimmed + key
        }
    }

    suspend fun setSyncFolderUri(uri: String?) {
        context.dataStore.edit {
            if (uri == null) it.remove(Keys.SYNC_FOLDER_URI) else it[Keys.SYNC_FOLDER_URI] = uri
        }
    }

    val theme: Flow<ThemeMode> = context.dataStore.data.map {
        runCatching { ThemeMode.valueOf(it[Keys.THEME] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
    }

    val ingestSource: Flow<IngestSource> = context.dataStore.data.map {
        runCatching { IngestSource.valueOf(it[Keys.INGEST_SOURCE] ?: "SMS") }
            .getOrDefault(IngestSource.SMS)
    }

    suspend fun readLamport(): Long =
        context.dataStore.data.map { it[Keys.LAMPORT] ?: 0L }.first()

    suspend fun writeLamport(value: Long) {
        context.dataStore.edit { it[Keys.LAMPORT] = value }
    }

    suspend fun setHousehold(id: String, inviteSecret: String) {
        context.dataStore.edit {
            it[Keys.HOUSEHOLD_ID] = id
            it[Keys.INVITE_SECRET] = inviteSecret
        }
    }

    suspend fun setSelf(uid: String, name: String, email: String) {
        context.dataStore.edit {
            it[Keys.SELF_UID] = uid
            it[Keys.SELF_NAME] = name
            it[Keys.SELF_EMAIL] = email
        }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDED] = value }
        fastPrefs.edit().putBoolean(FAST_ONBOARDED, value).apply()
    }

    /**
     * Synchronous mirror of [onboarded], read before the first frame.
     *
     * DataStore is coroutine-only, so deciding the start destination from it means
     * rendering nothing until a disk read completes — a visible stall on every launch.
     * SharedPreferences is memory-mapped and already loaded by the time the activity
     * exists, so this costs microseconds. DataStore stays the source of truth; this is
     * only a cache to get the first frame out.
     */
    fun onboardedBlocking(): Boolean = fastPrefs.getBoolean(FAST_ONBOARDED, false)

    private val fastPrefs by lazy {
        context.getSharedPreferences("ours_fast", Context.MODE_PRIVATE)
    }

    suspend fun setLastSync(at: Long, transport: String) {
        context.dataStore.edit {
            it[Keys.LAST_SYNC_AT] = at
            it[Keys.LAST_SYNC_TRANSPORT] = transport
        }
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setNearbyAlways(value: Boolean) {
        context.dataStore.edit { it[Keys.NEARBY_ALWAYS] = value }
    }

    suspend fun setAppLock(value: Boolean) {
        context.dataStore.edit { it[Keys.APP_LOCK] = value }
    }

    suspend fun setIngestSource(source: IngestSource) {
        context.dataStore.edit { it[Keys.INGEST_SOURCE] = source.name }
    }

    suspend fun setBackfillDone(value: Boolean) {
        context.dataStore.edit { it[Keys.BACKFILL_DONE] = value }
    }

    suspend fun isSeeded(): Boolean = context.dataStore.data.map { it[Keys.SEEDED] ?: false }.first()

    suspend fun setSeeded() {
        context.dataStore.edit { it[Keys.SEEDED] = true }
    }

    suspend fun snapshot(): Snapshot {
        val prefs = context.dataStore.data.first()
        return Snapshot(
            deviceId = prefs[Keys.DEVICE_ID] ?: deviceId(),
            householdId = prefs[Keys.HOUSEHOLD_ID],
            inviteSecret = prefs[Keys.INVITE_SECRET],
            selfUid = prefs[Keys.SELF_UID],
            selfName = prefs[Keys.SELF_NAME],
            selfEmail = prefs[Keys.SELF_EMAIL],
        )
    }

    private companion object {
        const val MAX_FIRED_ALERTS = 200
        const val FAST_ONBOARDED = "onboarded"
    }

    data class Snapshot(
        val deviceId: String,
        val householdId: String?,
        val inviteSecret: String?,
        val selfUid: String?,
        val selfName: String?,
        val selfEmail: String?,
    )
}
