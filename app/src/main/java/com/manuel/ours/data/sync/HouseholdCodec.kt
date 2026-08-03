package com.manuel.ours.data.sync

import com.manuel.ours.data.prefs.AppPrefs
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the household's encrypted log codec on demand.
 *
 * Exists so [NearbyTransport] can keep encrypting after the
 * transport interface moved from opaque strings to events. Both need the same key
 * derivation and the same per-line format, and duplicating it in two places is how
 * two implementations silently drift until one can no longer read the other's output.
 *
 * [SheetTransport] deliberately does not use this: a sheet you can read is the point.
 */
@Singleton
class HouseholdCodec @Inject constructor(
    private val prefs: AppPrefs,
) {
    /** Null when no household exists yet — there is nothing to encrypt to. */
    suspend fun codecOrNull(): LogCodec? {
        val snapshot = prefs.snapshot()
        val secret = snapshot.inviteSecret ?: return null
        val householdId = snapshot.householdId ?: return null
        return LogCodec(CryptoBox.deriveKey(secret, householdId))
    }

    suspend fun encode(events: List<SyncEvent>): String? =
        codecOrNull()?.encodeLines(events)

    suspend fun decode(text: String): List<SyncEvent> =
        codecOrNull()?.decodeLines(text).orEmpty()
}
