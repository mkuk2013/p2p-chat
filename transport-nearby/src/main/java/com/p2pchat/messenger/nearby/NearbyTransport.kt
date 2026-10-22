package com.p2pchat.messenger.nearby

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.io.InputStream
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A live-discovered Nearby endpoint. [hint] is the identity hint decoded
 *  from the endpoint name (SHA-256(peerId)[0..8]) — a hint only; the peer
 *  is bound to a real peerId by the identity-payload exchange that runs
 *  over the established connection, never by this value alone. */
data class NearbyEndpoint(
    val endpointId: String,
    val endpointName: String,
    val hint: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NearbyEndpoint) return false
        return endpointId == other.endpointId && endpointName == other.endpointName &&
            (hint?.contentEquals(other.hint ?: return false) ?: (other.hint == null))
    }

    override fun hashCode(): Int {
        var result = endpointId.hashCode()
        result = 31 * result + endpointName.hashCode()
        result = 31 * result + (hint?.contentHashCode() ?: 0)
        return result
    }
}

enum class NearbyLinkState { CONNECTING, CONNECTED, DISCONNECTED, FAILED }

data class NearbyBytes(val endpointId: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is NearbyBytes && endpointId == other.endpointId && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * endpointId.hashCode() + bytes.contentHashCode()
}

data class NearbyStream(val endpointId: String, val input: InputStream)

/**
 * Google Nearby Connections as an ADDITIONAL direct-link transport.
 *
 * Scope honesty: Nearby Connections is point-to-point only. It is used
 * for direct 1:1 sessions (faster message path + experimental voice
 * calls); the BLE mesh remains the multi-hop backbone and this transport
 * is never presented as relay-capable. When Google Play services is not
 * available on the device, [available] is false and everything no-ops —
 * the app keeps working over BLE alone.
 *
 * Identity: the advertised endpoint name carries only the same 8-byte
 * identity hint BLE uses. Trust is established exactly like BLE: after a
 * connection succeeds both sides exchange the signed-identity payload
 * (provided by [identityPayloadProvider]) and the app binds endpointId ↔
 * peerId from the verified payload — never from the endpoint name.
 */
class NearbyTransport(
    context: Context,
    private val localPeerId: String,
    private val identityPayloadProvider: () -> ByteArray,
) {
    private val appContext = context.applicationContext

    /** True when Google Play services is present and usable. */
    val available: Boolean = try {
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(appContext) == ConnectionResult.SUCCESS
    } catch (t: Throwable) {
        Log.w(TAG, "Play services check failed", t)
        false
    }

    private val client: ConnectionsClient? = if (available) {
        try {
            Nearby.getConnectionsClient(appContext)
        } catch (t: Throwable) {
            Log.w(TAG, "ConnectionsClient unavailable", t)
            null
        }
    } else {
        null
    }

    /** Endpoint name we advertise: "P2PChat-" + hex(identity hint). */
    val localEndpointName: String = NAME_PREFIX + hex(identityHint(localPeerId))

    private val _endpoints = MutableStateFlow<List<NearbyEndpoint>>(emptyList())
    val endpoints: StateFlow<List<NearbyEndpoint>> = _endpoints.asStateFlow()

    private val _sessions = MutableStateFlow<Map<String, NearbyLinkState>>(emptyMap())
    val sessions: StateFlow<Map<String, NearbyLinkState>> = _sessions.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<NearbyBytes>(extraBufferCapacity = 64)
    val incomingBytes: SharedFlow<NearbyBytes> = _incomingBytes.asSharedFlow()

    private val _incomingStreams = MutableSharedFlow<NearbyStream>(extraBufferCapacity = 8)
    val incomingStreams: SharedFlow<NearbyStream> = _incomingStreams.asSharedFlow()

    @Volatile
    private var started = false

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val hint = decodeHint(info.endpointName)
            _endpoints.update { list ->
                list.filterNot { it.endpointId == endpointId } +
                    NearbyEndpoint(endpointId, info.endpointName, hint)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            _endpoints.update { list -> list.filterNot { it.endpointId == endpointId } }
            if (_sessions.value[endpointId] == NearbyLinkState.CONNECTING) {
                setSession(endpointId, NearbyLinkState.FAILED)
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> payload.asBytes()?.let {
                    _incomingBytes.tryEmit(NearbyBytes(endpointId, it))
                }
                Payload.Type.STREAM -> payload.asStream()?.asInputStream()?.let {
                    _incomingStreams.tryEmit(NearbyStream(endpointId, it))
                }
                else -> Unit // FILE payloads are not used by this app.
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Byte payloads are small and fire-and-forget here; delivery truth
            // comes from the mesh ACK layer, not from transfer updates.
        }
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Accept the link; trust is NOT implied. The peer's identity is
            // verified from the identity payload exchanged after connect,
            // exactly as with a fresh BLE GATT connection.
            client?.acceptConnection(endpointId, payloadCallback)
                ?.addOnFailureListener { e ->
                    Log.w(TAG, "acceptConnection failed for $endpointId", e)
                    setSession(endpointId, NearbyLinkState.FAILED)
                }
            setSession(endpointId, NearbyLinkState.CONNECTING)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                setSession(endpointId, NearbyLinkState.CONNECTED)
                // Same as BLE: first thing on a fresh link is our identity.
                runCatching { identityPayloadProvider() }.getOrNull()?.let { identity ->
                    sendBytes(endpointId, identity)
                }
            } else {
                Log.w(TAG, "connection to $endpointId failed: ${resolution.status.statusCode}")
                setSession(endpointId, NearbyLinkState.FAILED)
            }
        }

        override fun onDisconnected(endpointId: String) {
            setSession(endpointId, NearbyLinkState.DISCONNECTED)
        }
    }

    /** Begin advertising + discovery. Idempotent; no-op when unavailable. */
    fun start() {
        val c = client ?: return
        if (started) return
        started = true
        val strategy = Strategy.P2P_POINT_TO_POINT
        c.startAdvertising(
            localEndpointName,
            SERVICE_ID,
            lifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { e ->
            Log.w(TAG, "startAdvertising failed", e)
            started = false
        }
        c.startDiscovery(
            SERVICE_ID,
            discoveryCallback,
            DiscoveryOptions.Builder().setStrategy(strategy).build(),
        ).addOnFailureListener { e -> Log.w(TAG, "startDiscovery failed", e) }
    }

    fun stop() {
        val c = client ?: return
        started = false
        runCatching { c.stopAllEndpoints() }
        runCatching { c.stopAdvertising() }
        runCatching { c.stopDiscovery() }
        _sessions.value = emptyMap()
    }

    /** Ask [endpointId] for a connection (identity binding happens after). */
    fun connect(endpointId: String) {
        val c = client ?: return
        if (_sessions.value[endpointId] == NearbyLinkState.CONNECTED ||
            _sessions.value[endpointId] == NearbyLinkState.CONNECTING
        ) {
            return
        }
        setSession(endpointId, NearbyLinkState.CONNECTING)
        c.requestConnection(localEndpointName, endpointId, lifecycleCallback)
            .addOnFailureListener { e ->
                Log.w(TAG, "requestConnection to $endpointId failed", e)
                // STATUS_ENDPOINT_IO_ERROR (1052) etc. — most commonly the
                // other side also needs to be advertising; discovery gives
                // us endpoints that are, so a failure here is terminal for
                // this attempt and is surfaced via the session state.
                setSession(endpointId, NearbyLinkState.FAILED)
            }
    }

    fun disconnect(endpointId: String) {
        runCatching { client?.disconnectFromEndpoint(endpointId) }
        setSession(endpointId, NearbyLinkState.DISCONNECTED)
    }

    /**
     * Send a BYTES payload. Returns false when the endpoint is not
     * connected, Play services is unavailable, or the payload exceeds the
     * Nearby BYTES limit (32,768 B) — in that case callers fall back to
     * the BLE path / outbox, exactly as designed.
     */
    fun sendBytes(endpointId: String, bytes: ByteArray): Boolean {
        val c = client ?: return false
        if (_sessions.value[endpointId] != NearbyLinkState.CONNECTED) return false
        if (bytes.size > MAX_BYTES_PAYLOAD) return false
        return try {
            c.sendPayload(endpointId, Payload.fromBytes(bytes))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendPayload(bytes) to $endpointId failed", t)
            false
        }
    }

    /** Send any payload (used for STREAM payloads by the call controller). */
    fun sendPayload(endpointId: String, payload: Payload): Boolean {
        val c = client ?: return false
        if (_sessions.value[endpointId] != NearbyLinkState.CONNECTED) return false
        return try {
            c.sendPayload(endpointId, payload)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "sendPayload to $endpointId failed", t)
            false
        }
    }

    fun connectedEndpointIds(): Set<String> =
        _sessions.value.filterValues { it == NearbyLinkState.CONNECTED }.keys

    fun linkState(endpointId: String): NearbyLinkState? = _sessions.value[endpointId]

    fun endpoint(endpointId: String): NearbyEndpoint? =
        _endpoints.value.firstOrNull { it.endpointId == endpointId }

    fun endpointByHint(hint: ByteArray): NearbyEndpoint? =
        _endpoints.value.firstOrNull { it.hint?.contentEquals(hint) == true }

    private fun setSession(endpointId: String, state: NearbyLinkState) {
        _sessions.update { it + (endpointId to state) }
    }

    companion object {
        private const val TAG = "NearbyTransport"
        const val SERVICE_ID = "com.p2pchat.messenger.NEARBY"
        const val NAME_PREFIX = "P2PChat-"
        const val MAX_BYTES_PAYLOAD = 32_768

        /** Same 8-byte identity hint as the BLE advertisements:
         *  first 8 bytes of SHA-256(peerId). */
        fun identityHint(peerId: String): ByteArray =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(peerId.toByteArray(Charsets.UTF_8))
                .copyOf(8)

        fun decodeHint(endpointName: String): ByteArray? {
            if (!endpointName.startsWith(NAME_PREFIX)) return null
            val hexPart = endpointName.removePrefix(NAME_PREFIX)
            if (hexPart.length != 16) return null
            return runCatching {
                ByteArray(8) { i ->
                    hexPart.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }.getOrNull()
        }

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }
}
