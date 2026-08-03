package com.manuel.ours.data.sync

import com.manuel.ours.data.prefs.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs through a Google Sheet, via an Apps Script web app.
 *
 * **Why a script rather than the Sheets API:** there is no unauthenticated way to
 * write to a sheet. The Sheets API needs OAuth, a Cloud project and a signing
 * fingerprint. An Apps Script deployed as "Anyone" gives a plain HTTPS endpoint that
 * needs none of that, and — importantly — lets the sheet itself stay **private**. Only
 * the script URL is shared between the two phones, so you never have to set the sheet
 * to "anyone with the link can edit".
 *
 * **The URL is the credential.** Anyone holding it can read every transaction and
 * delete rows. It is a password; it belongs in the same mental category. Re-deploying
 * the script issues a new URL and revokes the old one.
 *
 * **Deliberately unencrypted.** Every other transport encrypts; this one writes
 * plaintext because a sheet you cannot read defeats the purpose of using a sheet.
 * That is a conscious trade, not an oversight.
 */
@Singleton
class SheetTransport @Inject constructor(
    private val prefs: AppPrefs,
) : SyncTransport {

    override val name = "Sheet"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun isAvailable(): Boolean =
        !prefs.sheetUrlString().isNullOrBlank()

    override suspend fun push(deviceId: String, events: List<SyncEvent>) =
        withContext(Dispatchers.IO) {
            if (events.isEmpty()) return@withContext
            val url = prefs.sheetUrlString() ?: error("No sheet URL configured")

            // Batched into one request. A row-per-request design would take 170
            // round-trips on a first sync, and Apps Script cold-starts each one.
            val body = JSONObject()
                .put("action", "push")
                .put("deviceId", deviceId)
                .put("events", JSONArray(events.map { it.toJson() }))
                .toString()

            val response = post(url, body)
            if (!response.optBoolean("ok", false)) {
                error("Sheet rejected the push: ${response.optString("error", "unknown")}")
            }
        }

    override suspend fun pull(selfDeviceId: String): List<SyncEvent> =
        withContext(Dispatchers.IO) {
            val url = prefs.sheetUrlString() ?: return@withContext emptyList()

            // The cursor is a row index, not a timestamp: rows are append-only and
            // never reordered, so "everything after row N" is exact. A timestamp
            // cursor would drop events written while two phones raced.
            val since = prefs.sheetCursor()

            val body = JSONObject()
                .put("action", "pull")
                .put("deviceId", selfDeviceId)
                .put("since", since)
                .toString()

            val response = runCatching { post(url, body) }.getOrNull()
                ?: return@withContext emptyList()

            val rows = response.optJSONArray("events") ?: JSONArray()
            val events = buildList {
                for (i in 0 until rows.length()) {
                    // One malformed row — a hand-edit in the sheet, most likely —
                    // must not abort the whole sync.
                    runCatching { fromJson(rows.getJSONObject(i)) }.getOrNull()?.let(::add)
                }
            }

            response.optLong("cursor", -1L).takeIf { it >= 0 }?.let { prefs.setSheetCursor(it) }
            events.filter { it.deviceId != selfDeviceId }
        }

    /** Verifies the URL before the user leaves the settings screen. */
    suspend fun testConnection(url: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val response = post(url, JSONObject().put("action", "ping").toString())
            if (!response.optBoolean("ok", false)) {
                error(response.optString("error", "The script replied but rejected the request"))
            }
            response.optString("sheet", "Connected")
        }
    }

    // -- HTTP ---------------------------------------------------------------------

    private fun post(url: String, body: String): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 40_000
            setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
            // Apps Script answers /exec with a 302 to a googleusercontent.com host
            // that actually carries the body. Without following it you get an empty
            // response and a very confusing "sync does nothing" bug.
            instanceFollowRedirects = true
        }

        connection.outputStream.use { it.write(body.toByteArray()) }

        val text = if (connection.responseCode in 200..299) {
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        } else {
            val err = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText)
            error("HTTP ${connection.responseCode}: ${err?.take(200).orEmpty()}")
        }
        connection.disconnect()

        return runCatching { JSONObject(text) }.getOrElse {
            // Apps Script returns an HTML error page when the deployment is wrong —
            // typically "Execute as: Me / Who has access: Anyone" not being set.
            error(
                if (text.trimStart().startsWith("<")) {
                    "The URL returned a web page, not data. Check the deployment is set " +
                        "to \"Anyone\" access."
                } else "Unexpected reply from the sheet"
            )
        }
    }

    // -- Row format ---------------------------------------------------------------

    private fun SyncEvent.toJson(): JSONObject = JSONObject()
        .put("eventId", eventId)
        .put("txnId", txnId)
        .put("op", op.name)
        .put("lamport", lamport)
        .put("deviceId", deviceId)
        .put("ownerUid", ownerUid)
        .put("wallClock", wallClock)
        .put("payload", payload?.let { json.encodeToString(SyncPayload.serializer(), it) })

    private fun fromJson(row: JSONObject): SyncEvent = SyncEvent(
        eventId = row.getString("eventId"),
        txnId = row.getString("txnId"),
        op = SyncOp.valueOf(row.getString("op")),
        lamport = row.getLong("lamport"),
        deviceId = row.getString("deviceId"),
        ownerUid = row.optString("ownerUid"),
        wallClock = row.optLong("wallClock"),
        payload = row.optString("payload").takeIf { it.isNotBlank() && it != "null" }
            ?.let { json.decodeFromString(SyncPayload.serializer(), it) },
    )
}
