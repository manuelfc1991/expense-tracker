package com.manuel.ours.data.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM over each log line, with the household key derived from the invite
 * secret via HKDF-SHA256.
 *
 * Sync is Bluetooth-only and never touches a server, so this is defence in depth
 * rather than the last line: a paired-but-untrusted device, a captured radio frame,
 * or a future transport all see ciphertext. Cheap to keep, expensive to retrofit.
 */
object CryptoBox {

    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12
    private val random = SecureRandom()

    /** HKDF-SHA256 (RFC 5869), extract-then-expand, 32-byte output. */
    fun deriveKey(inviteSecret: String, householdId: String): ByteArray {
        val salt = "ours:$householdId".toByteArray(Charsets.UTF_8)
        val ikm = inviteSecret.toByteArray(Charsets.UTF_8)

        val prk = hmac(salt, ikm)
        val info = "ours-sync-v1".toByteArray(Charsets.UTF_8)
        val t = hmac(prk, info + byteArrayOf(1))
        return t.copyOf(32)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An all-zero key is only ever hit by a degenerate empty salt; SecretKeySpec
        // rejects a zero-length array outright, so substitute rather than crash.
        val material = if (key.isEmpty()) ByteArray(32) else key
        mac.init(SecretKeySpec(material, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** Output is nonce || ciphertext || tag, so it is self-contained per line. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray {
        require(blob.size > NONCE_BYTES) { "ciphertext too short" }
        val nonce = blob.copyOfRange(0, NONCE_BYTES)
        val body = blob.copyOfRange(NONCE_BYTES, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return cipher.doFinal(body)
    }

    /** Human-typeable invite secret. Avoids 0/O and 1/I/L. */
    fun generateInviteSecret(length: Int = 6): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..length)
            .map { alphabet[random.nextInt(alphabet.length)] }
            .joinToString("")
    }
}
