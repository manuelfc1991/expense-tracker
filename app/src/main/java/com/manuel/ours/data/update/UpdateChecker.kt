package com.manuel.ours.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.manuel.ours.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Self-update for an app that will never be on Play.
 *
 * A household sideloads this, which until now meant somebody rebuilding, copying an
 * APK to a phone and walking it across the room every time a parser rule changed. The
 * manifest lives in the project's own public repository, so there is no server to run
 * and nothing to pay for.
 */
@Singleton
class UpdateChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class Available(
        val versionName: String,
        val versionCode: Int,
        val notes: String,
        val apkUrl: String,
        val sizeBytes: Long,
    )

    sealed interface Result {
        data object UpToDate : Result
        data class Update(val available: Available) : Result
        data class Failed(val reason: String) : Result
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val body = get(MANIFEST_URL)
            val json = JSONObject(body)
            val code = json.getInt("versionCode")
            if (code <= BuildConfig.VERSION_CODE) return@runCatching Result.UpToDate

            Result.Update(
                Available(
                    versionName = json.optString("versionName", code.toString()),
                    versionCode = code,
                    notes = json.optString("notes").trim(),
                    apkUrl = json.getString("apkUrl"),
                    sizeBytes = json.optLong("sizeBytes", 0L),
                )
            )
        }.getOrElse { Result.Failed(it.message ?: "could not reach the update manifest") }
    }

    /**
     * Downloads and verifies, returning the file only if it is genuinely a newer build
     * of *this* app.
     *
     * The signing certificate is checked against the running app's own before the
     * installer is ever offered. Android would refuse a mismatched signature anyway,
     * but it refuses at the end of a 25 MB download with a message that explains
     * nothing — and an app that downloads and opens whatever a URL hands it deserves to
     * check what it got.
     */
    suspend fun download(available: Available): kotlin.Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // One filename, always overwritten: keeping every version downloaded would
            // quietly grow to hundreds of megabytes in a cache nobody looks at.
            val file = File(dir, "ours-update.apk")

            (URL(available.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 120_000
                instanceFollowRedirects = true
            }.use { connection ->
                if (connection.responseCode !in 200..299) {
                    error("Download failed: HTTP ${connection.responseCode}")
                }
                connection.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
            }

            val theirs = signatureOf(file)
                ?: error("That file is not a readable Android package")
            val ours = installedSignature()
                ?: error("Could not read this app's own signature")

            if (!theirs.contentEquals(ours)) {
                file.delete()
                error("That build was signed by a different key, so it is not an update to this app")
            }
            file
        }
    }

    private fun get(url: String): String =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
        }.use { connection ->
            if (connection.responseCode !in 200..299) {
                error("HTTP ${connection.responseCode} from the update manifest")
            }
            connection.inputStream.bufferedReader().use(BufferedReader::readText)
        }

    private fun signatureOf(apk: File): ByteArray? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
            ?: return null
        digestOf(info)
    }.getOrNull()

    private fun installedSignature(): ByteArray? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        digestOf(context.packageManager.getPackageInfo(context.packageName, flags))
    }.getOrNull()

    private fun digestOf(info: android.content.pm.PackageInfo): ByteArray? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION") info.signatures?.firstOrNull()?.toByteArray()
        } ?: return null
        return MessageDigest.getInstance("SHA-256").digest(raw)
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try { block(this) } finally { disconnect() }

    companion object {
        /**
         * Hard-coded on purpose.
         *
         * A configurable update URL is a setting that lets anyone who can reach the
         * phone point it at their own build. There is one project and one repository;
         * this is not worth making flexible.
         */
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/manuelfc1991/expense-tracker/master/release/version.json"
    }
}
