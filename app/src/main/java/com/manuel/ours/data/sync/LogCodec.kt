package com.manuel.ours.data.sync

import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Encodes a log as JSONL where **each line is independently encrypted**.
 *
 * Per-line rather than whole-file encryption is what makes the log append-only over
 * the network too: a device can upload only its new lines, and a corrupted or
 * truncated line costs you that one event rather than the entire history.
 */
class LogCodec(private val key: ByteArray) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeLines(events: List<SyncEvent>): String =
        events.joinToString("\n") { encodeLine(it) }

    fun encodeLine(event: SyncEvent): String {
        val plain = json.encodeToString(SyncEvent.serializer(), event)
        val sealed = CryptoBox.encrypt(key, plain.toByteArray(Charsets.UTF_8))
        return base64Encode(sealed)
    }

    /**
     * Skips lines that fail to decrypt or parse instead of throwing. A single bad line
     * — a partial upload, a key rotation, a truncated Bluetooth frame — must not take
     * down the whole sync.
     */
    fun decodeLines(text: String): List<SyncEvent> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { decodeLineOrNull(it) }
            .toList()

    fun decodeLineOrNull(line: String): SyncEvent? = try {
        val plain = CryptoBox.decrypt(key, base64Decode(line))
        json.decodeFromString(SyncEvent.serializer(), plain.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    }

    /**
     * java.util.Base64, not android.util.Base64 — the Android one is a stub in plain
     * JVM unit tests that returns null rather than throwing, which silently produced
     * empty logs. The JDK version is available from API 26 and is our minSdk.
     */
    private fun base64Encode(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(bytes)

    private fun base64Decode(text: String): ByteArray =
        Base64.getDecoder().decode(text)
}
