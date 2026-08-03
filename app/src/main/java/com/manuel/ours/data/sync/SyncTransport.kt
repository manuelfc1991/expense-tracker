package com.manuel.ours.data.sync

/**
 * How sync events travel between the two phones.
 *
 * The interface deals in [SyncEvent] rather than opaque encrypted strings. It used to
 * be the latter, which suited Bluetooth frames and files — both move one blob per
 * device — but not a spreadsheet, where the natural unit is a *row*. Making a sheet
 * pretend it holds one opaque blob per device would render it unreadable, which is the
 * whole reason for using a sheet.
 *
 * Encryption is therefore each transport's own business: [NearbyTransport] and
 * [NearbyTransport] encrypts before handing bytes over, while [SheetTransport]
 * deliberately writes plaintext so a human can read it.
 *
 * What did *not* change is [SyncEngine] and [LogMerger]. Convergence, Lamport ordering
 * and conflict resolution are defined over the event set alone and know nothing about
 * transports — which is why every convergence test still passes untouched.
 */
interface SyncTransport {

    val name: String

    /** Cheap check — is this transport usable right now? */
    suspend fun isAvailable(): Boolean

    /**
     * Publish this device's events.
     *
     * Must be idempotent: after a failed round the same events are pushed again, and
     * they must not appear twice on the other side.
     */
    suspend fun push(deviceId: String, events: List<SyncEvent>)

    /**
     * Fetch everything this device has not already seen, from every peer.
     *
     * Handing a device its own events back is harmless — the merge is idempotent — but
     * wasteful, so implementations filter out [selfDeviceId] where they can.
     */
    suspend fun pull(selfDeviceId: String): List<SyncEvent>
}

/** Result of one sync attempt, surfaced in the status pill. */
data class SyncOutcome(
    val transport: String,
    val pulledEvents: Int,
    val appliedEvents: Int,
    val pushedEvents: Int,
    val error: Throwable? = null,
) {
    val success: Boolean get() = error == null
}
