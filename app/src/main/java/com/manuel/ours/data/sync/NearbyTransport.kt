package com.manuel.ours.data.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.manuel.ours.data.prefs.AppPrefs
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Instant sync when both phones are in the same room.
 *
 * Uses Nearby Connections rather than raw RFCOMM sockets: it negotiates BLE for
 * discovery then Bluetooth Classic or Wi-Fi Direct for the transfer, and handles
 * pairing itself. Hand-rolling that over BluetoothSocket is a great deal of code to
 * arrive at something worse.
 *
 * This is now the **only** way the two phones exchange data, so a failure is not
 * silent: the sync pill in the UI shows how long it has been since the last
 * successful exchange, and how many changes are still waiting to be sent.
 */
@Singleton
class NearbyTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPrefs,
    private val codec: HouseholdCodec,
    private val eventDao: com.manuel.ours.data.db.SyncEventDao,
) : SyncTransport {

    override val name = "Bluetooth"

    private val client by lazy { Nearby.getConnectionsClient(context) }

    /** endpointId -> received log text */
    private val received = ConcurrentHashMap<String, String>()

    /** endpointId -> peer deviceId, advertised in the connection name. */
    private val peerDeviceIds = ConcurrentHashMap<String, String>()

    /**
     * One round at a time.
     *
     * This is a @Singleton and both `NearbySyncService` and the periodic `SyncWorker` can
     * drive an exchange. A second `pull` cleared `received` and replaced
     * `exchangeComplete` while the first was awaiting it, so the first round returned an
     * empty peer list — read as "nothing to merge" rather than as an error — and had its
     * outgoing blob swapped mid-flight.
     */
    private val roundLock = kotlinx.coroutines.sync.Mutex()

    @Volatile
    private var pending: List<SyncEvent> = emptyList()
    @Volatile
    private var exchangeComplete: CompletableDeferred<Unit>? = null

    /** Whether this round actually reached a peer. See [push]. */
    @Volatile
    private var reachedPeer = false

    /** Encrypted blob to transmit this round, prepared before the session opens. */
    @Volatile
    private var outgoing: String? = null

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        hasPermissions() && prefs.snapshot().householdId != null
    }

    /**
     * Stages this device's events for the next exchange.
     *
     * Nearby is one bidirectional session rather than separate calls, so the actual
     * transmission happens inside [pull]. **This used to be a real bug**: the engine
     * calls pull *before* push, so on a fresh install nothing was staged when the
     * session ran and the device transmitted nothing at all. Both phones did the same,
     * so Bluetooth sync never worked once. [sync] now stages before opening the
     * session, which is why [exchange] takes the payload directly.
     */
    override suspend fun push(deviceId: String, events: List<SyncEvent>) {
        // Nothing is delivered here — the exchange already happened in [pull]. So this
        // must refuse when no peer was reached, or the engine marks the batch pushed and
        // drops it from the outbound queue having sent it to nobody. Recovering from that
        // needs a manual re-upload of the entire log.
        check(reachedPeer) { "No peer reached in this round" }
        pending = events
    }

    /**
     * Runs one exchange and returns whatever peers sent.
     *
     * Reads this device's unsent events itself rather than relying on a prior [push],
     * because the engine's pull-then-push order would otherwise leave the payload
     * empty on the first round — the bug that stopped Bluetooth working entirely.
     */
    override suspend fun pull(selfDeviceId: String): List<SyncEvent> =
        withContext(Dispatchers.IO) {
          roundLock.withLock {
            if (!hasPermissions()) return@withContext emptyList()
            val householdId = prefs.snapshot().householdId ?: return@withContext emptyList()

            received.clear()
            peerDeviceIds.clear()
            exchangeComplete = CompletableDeferred()
            outgoing = codec.encode(pending.ifEmpty { unsentEvents() })
            // Cleared once staged. It was never cleared, so the round *after* a
            // successful one re-transmitted the previous batch instead of the new events,
            // which the engine then marked pushed anyway.
            pending = emptyList()
            reachedPeer = false

            val serviceId = "$SERVICE_PREFIX.$householdId"

            try {
                startAdvertising(selfDeviceId, serviceId)
                startDiscovery(serviceId)

                // Bounded: if nobody is nearby we must not hold the sync worker open.
                withTimeoutOrNull(EXCHANGE_TIMEOUT_MS) { exchangeComplete?.await() }

                // A peer that sent us a blob is a peer that received ours — the exchange
                // is symmetric. This is what lets [push] tell "delivered" from "timed out
                // with nobody there", which it previously could not.
                reachedPeer = received.isNotEmpty()

                buildList {
                    received.forEach { (endpointId, blob) ->
                        val peer = peerDeviceIds[endpointId] ?: return@forEach
                        if (peer == selfDeviceId) return@forEach
                        addAll(codec.decode(blob))
                    }
                }
            } catch (_: Exception) {
                emptyList()
            } finally {
                stop()
            }
          }
        }

    /**
     * Reads the outbound queue directly.
     *
     * The engine calls pull before push, so on the first round nothing has been
     * staged. Returning an empty list here would silently transmit nothing — exactly
     * the failure that made Bluetooth sync appear to work while moving no data.
     */
    private suspend fun unsentEvents(): List<SyncEvent> =
        eventDao.unpushed().mapNotNull { entity ->
            runCatching {
                SyncEvent(
                    eventId = entity.eventId,
                    txnId = entity.txnId,
                    op = SyncOp.valueOf(entity.op),
                    lamport = entity.lamport,
                    deviceId = entity.deviceId,
                    ownerUid = entity.ownerUid,
                    wallClock = entity.wallClock,
                    payload = entity.payloadJson?.let {
                        kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                            .decodeFromString(SyncPayload.serializer(), it)
                    },
                )
            }.getOrNull()
        }

    private fun startAdvertising(selfDeviceId: String, serviceId: String) {
        client.startAdvertising(
            selfDeviceId,
            serviceId,
            connectionLifecycle,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        )
    }

    private fun startDiscovery(serviceId: String) {
        client.startDiscovery(
            serviceId,
            endpointDiscovery,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        )
    }

    fun stop() {
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
    }

    private val endpointDiscovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // The service ID already scopes discovery to our household, so anything
            // found here is our own pair of phones.
            peerDeviceIds[endpointId] = info.endpointName
            runCatching {
                client.requestConnection(
                    prefs.hashCode().toString(),
                    endpointId,
                    connectionLifecycle,
                )
            }
        }

        override fun onEndpointLost(endpointId: String) {
            peerDeviceIds.remove(endpointId)
        }
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            peerDeviceIds[endpointId] = info.endpointName
            // Auto-accept: the household service ID plus payload encryption already
            // gate this, and a confirmation dialog on both phones would make routine
            // sync a chore.
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode != ConnectionsStatusCodes.STATUS_OK) {
                exchangeComplete?.complete(Unit)
                return
            }
            outgoing?.let { blob ->
                client.sendPayload(endpointId, Payload.fromBytes(blob.toByteArray()))
            }
        }

        override fun onDisconnected(endpointId: String) {
            exchangeComplete?.complete(Unit)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { received[endpointId] = String(it) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                exchangeComplete?.complete(Unit)
            }
        }
    }

    fun hasPermissions(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun requiredPermissions(): List<String> = Companion.requiredPermissions()

    companion object {
        /**
         * Declared in the manifest is not the same as granted.
         *
         * Every entry point here is gated on [hasPermissions], so without a runtime
         * grant Nearby does not fail loudly — it reports "no peers" forever and the
         * toggle looks like it is on and working. The UI must therefore ask for these
         * at the moment the user enables nearby sync.
         */
        fun requiredPermissions(): List<String> = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }

        private val STRATEGY = Strategy.P2P_CLUSTER
        private const val SERVICE_PREFIX = "com.manuel.ours.sync"
        private const val EXCHANGE_TIMEOUT_MS = 20_000L
    }
}
