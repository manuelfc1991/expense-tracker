package com.manuel.ours.data.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.manuel.ours.data.prefs.AppPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Syncs through a folder the user picks with the system file picker.
 *
 * The point of doing it this way rather than calling a cloud API: **there is nothing
 * to configure.** No OAuth client, no Google Cloud project, no API key, no SHA-1
 * fingerprint. The user taps "Choose folder", picks somewhere in Drive, OneDrive,
 * Dropbox or local storage, and that provider's own app does the networking.
 *
 * Which is also why this app still holds **no `INTERNET` permission**. We hand bytes
 * to a `DocumentsProvider`; whether those bytes end up in a datacentre or on the SD
 * card is the provider's business, not ours. The privacy property survives a feature
 * that would normally destroy it.
 *
 * Sharing is the provider's job too: you share the folder with your partner in the
 * Drive app by hand, and they pick the same folder on their phone.
 */
@Singleton
class SafFolderTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPrefs,
    private val codec: HouseholdCodec,
) : SyncTransport {

    override val name = "Folder"

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        val uri = folderUri() ?: return@withContext false
        // The grant can be revoked from system settings, or the provider uninstalled.
        // Checking the persisted list is cheaper and more honest than discovering it
        // by exception halfway through a write.
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    override suspend fun push(deviceId: String, events: List<SyncEvent>) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext
        // Files hold this device's *whole* log, not a delta, so a peer joining later
        // sees the full history rather than only what happened after it arrived.
        val whole = LogMerger.compact(readOwn(deviceId) + events)
        val encryptedLog = codec.encode(whole) ?: error("No household key")

        val tree = folderUri() ?: error("No sync folder chosen")
        val fileName = fileNameFor(deviceId)
        val existing = listLogFiles(tree).firstOrNull { it.fileName == fileName }?.uri

        val target = existing ?: DocumentsContract.createDocument(
            context.contentResolver,
            childrenParent(tree),
            MIME_TYPE,
            fileName,
        ) ?: error("Could not create $fileName in the sync folder")

        writeTruncating(target, encryptedLog)
    }

    override suspend fun pull(selfDeviceId: String): List<SyncEvent> =
        withContext(Dispatchers.IO) {
            val tree = folderUri() ?: return@withContext emptyList()
            buildList {
                for (entry in listLogFiles(tree)) {
                    val peer = deviceIdFromFileName(entry.fileName) ?: continue
                    if (peer == selfDeviceId) continue
                    // One unreadable peer file must not abort the whole sync round.
                    val text = runCatching { readText(entry.uri) }.getOrNull() ?: continue
                    addAll(codec.decode(text))
                }
            }
        }

    /** This device's previously written log, so a push extends rather than replaces. */
    private suspend fun readOwn(deviceId: String): List<SyncEvent> {
        val tree = folderUri() ?: return emptyList()
        val own = listLogFiles(tree).firstOrNull { it.fileName == fileNameFor(deviceId) }
            ?: return emptyList()
        return runCatching { codec.decode(readText(own.uri)) }.getOrDefault(emptyList())
    }

    // -- Storage Access Framework plumbing ---------------------------------------

    private data class Entry(val uri: Uri, val fileName: String)

    private suspend fun folderUri(): Uri? = prefs.syncFolderUriString()?.let(Uri::parse)

    private fun childrenParent(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /**
     * One cursor for the whole folder. `DocumentFile.findFile` issues a fresh query
     * per lookup, which over a network-backed provider turns a two-file sync into a
     * visible stall.
     */
    private fun listLogFiles(tree: Uri): List<Entry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )

        return context.contentResolver.query(childrenUri, projection, null, null, null)
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0) ?: continue
                        val displayName = cursor.getString(1) ?: continue
                        if (!displayName.startsWith(PREFIX)) continue
                        add(
                            Entry(
                                uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                                fileName = displayName,
                            )
                        )
                    }
                }
            }.orEmpty()
    }

    private fun readText(uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            .orEmpty()

    /**
     * "wt" truncates; plain "w" on some providers leaves the tail of a previously
     * longer file behind. A stale tail would not corrupt the merge — every log line is
     * independently encrypted and the merge is idempotent — but it would grow the file
     * forever, so fall back to delete-and-recreate when truncation is unsupported.
     */
    private fun writeTruncating(uri: Uri, content: String) {
        val wrote = runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(content.toByteArray())
            } ?: error("no output stream")
        }.isSuccess

        if (wrote) return

        DocumentsContract.deleteDocument(context.contentResolver, uri)
        error("Sync folder does not support truncating writes; file recreated next round")
    }

    companion object {
        private const val PREFIX = "device-"
        private const val SUFFIX = ".jsonl"
        const val MIME_TYPE = "application/octet-stream"

        fun fileNameFor(deviceId: String) = "$PREFIX$deviceId$SUFFIX"

        /**
         * Providers are not obliged to keep the display name we asked for. Drive will
         * happily hand back "device-abc.jsonl (1)" after a conflicting upload, or
         * append its own extension. Matching loosely on the prefix and stripping
         * anything after the device id keeps a renamed file syncing instead of
         * silently becoming a stranger's log.
         */
        fun deviceIdFromFileName(fileName: String): String? {
            if (!fileName.startsWith(PREFIX)) return null
            val afterPrefix = fileName.removePrefix(PREFIX)
            val id = afterPrefix.substringBefore(SUFFIX).trim()
            return id.takeIf { it.isNotEmpty() }
        }
    }
}
