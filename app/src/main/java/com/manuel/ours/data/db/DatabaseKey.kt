package com.manuel.ours.data.db

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.SecureRandom

/**
 * Supplies the SQLCipher passphrase for the transactions database.
 *
 * The passphrase is 32 random bytes generated once on this device and stored in
 * `EncryptedSharedPreferences`, whose own key lives in the Android Keystore — hardware
 * backed where the phone supports it. It is never derived from anything the user
 * types, never leaves the device, and never crosses to the other phone.
 *
 * The threat this addresses is specific and worth naming: the database holds the full
 * text of every bank SMS, including account tails and balances. Before this, anything
 * with filesystem access — an ADB backup, a rooted phone, a forensic dump of a lost
 * handset — could read it as plain SQLite. The Settings screen already promised the
 * data was private; this is what makes that true.
 */
object DatabaseKey {

    private const val PREFS_NAME = "ours_secure"
    private const val KEY_PASSPHRASE = "db_passphrase"

    /**
     * The passphrase handed to SQLCipher, in **raw key** form: `x'<64 hex chars>'`.
     *
     * This is the single biggest startup win in the app. Given an ordinary passphrase,
     * SQLCipher runs PBKDF2 with 256,000 iterations — per connection — which measured
     * at 650–1000 ms each on this device, several times over during launch. Raw-key
     * form skips derivation entirely and uses the bytes as the AES key directly.
     *
     * That costs nothing in security *here*, and only here: PBKDF2 exists to stretch a
     * low-entropy human password into a key. This key is already 32 bytes straight
     * from [SecureRandom], so there is no entropy to stretch — iterating a random
     * 256-bit key a quarter of a million times protects against nothing. It would be
     * badly wrong to do this with a user-chosen password.
     */
    fun getOrCreate(context: Context): ByteArray = rawKeySpec(getOrCreateKeyBytes(context))

    private fun rawKeySpec(key: ByteArray): ByteArray {
        val hex = key.joinToString("") { "%02x".format(it) }
        return "x'$hex'".toByteArray(Charsets.US_ASCII)
    }

    /** Bytes, not a String: a String literal lingers in the heap until GC. */
    private fun getOrCreateKeyBytes(context: Context): ByteArray {
        val prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

        prefs.getString(KEY_PASSPHRASE, null)?.let {
            return Base64.decode(it, Base64.NO_WRAP)
        }

        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString(KEY_PASSPHRASE, Base64.encodeToString(fresh, Base64.NO_WRAP))
            .apply()
        return fresh
    }

    /**
     * Removes the pre-encryption plaintext database.
     *
     * SQLCipher cannot open an unencrypted file, so leaving it behind would crash the
     * app on launch for anyone upgrading. Deleting is correct rather than migrating:
     * the entire contents are re-derivable from the SMS inbox in about two minutes,
     * and copying rows out of a plaintext file to encrypt them would rewrite the very
     * data we are trying to stop existing in the clear.
     */
    fun deleteLegacyPlaintextDatabase(context: Context) {
        val dir = File(context.getDatabasePath(LEGACY_NAMES.first()).parent)
        LEGACY_NAMES.forEach { base ->
            listOf(base, "$base-shm", "$base-wal", "$base-journal").forEach { name ->
                File(dir, name).takeIf { it.exists() }?.delete()
            }
        }
    }

    /**
     * Databases from earlier schemes that can no longer be opened:
     *  - `ours.db` was plaintext, before encryption existed.
     *  - `ours-encrypted.db` was keyed via PBKDF2; the raw-key change makes its
     *    header undecryptable, and SQLCipher would throw rather than migrate.
     *
     * Deleting is right in both cases: every row is re-derivable from the SMS inbox
     * in about two minutes, and a migration would mean decrypting and rewriting the
     * whole file for no lasting benefit.
     */
    private val LEGACY_NAMES = listOf("ours.db", "ours-encrypted.db")
}
