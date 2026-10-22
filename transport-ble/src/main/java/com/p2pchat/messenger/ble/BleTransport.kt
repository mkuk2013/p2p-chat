package com.p2pchat.messenger.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.p2pchat.messenger.ble.model.BlePeer
import com.p2pchat.messenger.ble.model.ConnState
import com.p2pchat.messenger.ble.model.IdentityLinkStatus
import com.p2pchat.messenger.ble.model.IdentityPhase
import com.p2pchat.messenger.ble.model.IncomingData
import com.p2pchat.messenger.core.transport.ChunkReassembler
import com.p2pchat.messenger.core.transport.GattTieBreak
import com.p2pchat.messenger.core.transport.IdentityResolver
import com.p2pchat.messenger.core.transport.LinkTestFrames
import com.p2pchat.messenger.core.transport.LinkTestResult
import com.p2pchat.messenger.core.transport.PacketChunker
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Real BLE transport for P2P Chat: scanning, advertising, GATT server and
 * GATT client in one class, because every mesh node plays both roles.
 *
 * Sending path: [send] splits the payload with [PacketChunker] using a
 * per-connection payload size derived from the negotiated MTU, then writes
 * the chunks **serially** over one of two routes:
 *  1. preferred — we are GATT client to the peer: write to their TX char;
 *  2. fallback — the peer is GATT client to us: notify them on our RX char.
 *
 * Receiving path: chunk bytes arrive either on our server's TX char
 * (peer-as-client writes) or as notifications from a server we connected to;
 * both feed a per-address [ChunkReassembler] and complete payloads are
 * emitted on [incoming] fully reassembled.
 *
 * Honest limits (also in the app README):
 *  - at most [MAX_CONNECTIONS] simultaneous GATT links;
 *  - Bluetooth is never enabled silently — [isBluetoothEnabled] only reports
 *    state; the app shows Android's official enable prompt;
 *  - Android may throttle scans/advertising; failures surface via state,
 *    nothing here pretends a link exists when it does not.
 */
class BleTransport(
    private val context: Context,
    private val localPeerId: String,
    private val identityPayloadProvider: () -> ByteArray,
) {

    private val appContext: Context = context.applicationContext
    private val bluetoothManager: BluetoothManager? =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val handler = Handler(Looper.getMainLooper())

    // ---------------------------------------------------------------- flows

    private val _peers = MutableStateFlow<List<BlePeer>>(emptyList())
    val peers: StateFlow<List<BlePeer>> = _peers.asStateFlow()

    private val _connections = MutableStateFlow<Map<String, ConnState>>(emptyMap())
    val connections: StateFlow<Map<String, ConnState>> = _connections.asStateFlow()

    private val _incoming = MutableSharedFlow<IncomingData>(extraBufferCapacity = 64)
    val incoming: SharedFlow<IncomingData> = _incoming.asSharedFlow()

    /** Raw IDENTITY payloads read from peers, keyed by device address. The app
     * layer decodes them (peerId / keys / name) and calls [setPeerIdentity]. */
    private val _peerIdentities = MutableStateFlow<Map<String, ByteArray>>(emptyMap())
    val peerIdentities: StateFlow<Map<String, ByteArray>> = _peerIdentities.asStateFlow()

    /** Plain-words status of the identity exchange per device address —
     * the diagnostics line the UI shows while connecting, and the trail
     * mirrored to [diagnosticsListener]. Bounded by design: a stalled
     * exchange ends FAILED here with the reason, never spins forever. */
    private val _identityStatus = MutableStateFlow<Map<String, IdentityLinkStatus>>(emptyMap())
    val identityStatus: StateFlow<Map<String, IdentityLinkStatus>> = _identityStatus.asStateFlow()

    /** Optional sink for plain-words link events (the app wires this to
     * the crash-report breadcrumb trail). Invoked on radio threads —
     * implementations must be cheap and non-blocking. */
    @Volatile
    var diagnosticsListener: ((String) -> Unit)? = null

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    // ---------------------------------------------------------------- state

    private val peerMap = ConcurrentHashMap<String, BlePeer>()
    private val clientGatts = ConcurrentHashMap<String, BluetoothGatt>()
    private val serverDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val subscribedServerPeers = ConcurrentHashMap.newKeySet<String>()
    private val reassemblers = ConcurrentHashMap<String, ChunkReassembler>()
    private val mtuByAddress = ConcurrentHashMap<String, Int>()
    private val sendMutexByAddress = ConcurrentHashMap<String, Mutex>()
    private val writeAckWaiters = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<Boolean>>()

    // -- Connection-speed check (v1.2.0): ping/pong waiters keyed by
    // frame sequence, and a single-test-at-a-time guard. Test frames
    // ride the normal data channel and are demuxed in
    // handleIncomingChunk, so the numbers describe the real link.
    private val pongWaiters = ConcurrentHashMap<Int, kotlinx.coroutines.CompletableDeferred<Long>>()
    private val linkTestInFlight = AtomicBoolean(false)
    private val linkTestSeq = AtomicInteger(0)
    private val connectTimeouts = ConcurrentHashMap<String, Runnable>()
    private val reconnectAttempts = ConcurrentHashMap<String, Int>()

    // -- Identity resolution (v1.1.1): bounded state machine per address,
    // a strictly serialized GATT operation queue per link, and the set of
    // links whose identity announcement has already been sent.
    private val identityResolvers = ConcurrentHashMap<String, IdentityResolver>()
    private val identityTimeouts = ConcurrentHashMap<String, Runnable>()
    private val identityAnnounced = ConcurrentHashMap.newKeySet<String>()

    /** Addresses whose resolver session must survive the teardown of a
     * resolver-driven link reset (marked just before the reset, cleared
     * when the fresh link's discovery resumes the session). Without
     * this, the old link's late DISCONNECTED callback would wipe the
     * session budget and the bounded exchange could loop forever. */
    private val resolutionResetsInFlight = ConcurrentHashMap.newKeySet<String>()

    /** One outstanding GATT operation per link, enforced. Android's
     * BluetoothGatt silently DROPS an operation issued while another is
     * in flight (it returns false and no callback ever arrives) — the
     * v1.1.0 hang was the IDENTITY read issued in the same breath as
     * the CCCD descriptor write in onServicesDiscovered. */
    private sealed class GattOp {
        object WriteCccd : GattOp()
        object ReadIdentity : GattOp()
    }

    private val gattOpLock = Any()
    private val gattOpQueues = ConcurrentHashMap<String, ArrayDeque<GattOp>>()
    private val gattOpInFlight = ConcurrentHashMap.newKeySet<String>()
    private val readBusyRetries = ConcurrentHashMap<String, Int>()
    private val manualDisconnects = ConcurrentHashMap.newKeySet<String>()
    private val knownAddresses = ConcurrentHashMap.newKeySet<String>()

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var serverRxCharacteristic: BluetoothGattCharacteristic? = null

    private val transferIdCounter = AtomicLong(System.currentTimeMillis())

    /** When true (default), a dropped link to a known peer is retried with
     * exponential backoff (max [MAX_RECONNECT_ATTEMPTS] attempts). */
    var autoReconnect: Boolean = true

    /** When true, if a filtered scan yields nothing the app layer may restart
     * scanning unfiltered (some OEMs mishandle 128-bit UUID filters). */
    var useUnfilteredFallback: Boolean = false

    companion object {
        const val MAX_CONNECTIONS: Int = 5
        const val CONNECT_TIMEOUT_MS: Long = 10_000L
        const val MAX_RECONNECT_ATTEMPTS: Int = 3
        const val REQUESTED_MTU: Int = 517
        const val DEFAULT_MTU: Int = 23
        const val MAX_PAYLOAD_CAP: Int = 512
        const val REASSEMBLY_TIMEOUT_MS: Long = 15_000L

        /** Interval of the identity-resolution watchdog tick. */
        const val WATCHDOG_INTERVAL_MS: Long = 1_000L

        /** Times a stack-refused identity read is re-queued (another
         * operation momentarily in flight) before it counts as a
         * failed resolver attempt. */
        const val MAX_READ_BUSY_RETRIES: Int = 5
        const val READ_BUSY_RETRY_DELAY_MS: Long = 300L

        /** Connection-speed check shape: a few small pings for the
         * round-trip time, then a burst of full-size frames timed end
         * to end — the honest app-channel rate, radio pacing included. */
        const val LINK_TEST_PINGS: Int = 5
        const val LINK_TEST_PING_BYTES: Int = 100
        const val LINK_TEST_PONG_TIMEOUT_MS: Long = 2_000L
        const val LINK_TEST_BURST_FRAMES: Int = 40
        const val LINK_TEST_FRAME_BYTES: Int = 512

        /** First 8 bytes of SHA-256(peerId) — the advertisement identity hint. */
        fun identityHint(peerId: String): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest(peerId.toByteArray(Charsets.UTF_8))
                .copyOf(8)
    }

    // ------------------------------------------------------------- BT state

    fun isBluetoothSupported(): Boolean = adapter != null

    /** Reports state only. NEVER enables Bluetooth — that is the user's
     * decision via Android's official enable prompt, launched by the app. */
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    private fun activeConnectionCount(): Int =
        _connections.value.count { it.value != ConnState.DISCONNECTED && it.value != ConnState.FAILED }

    private fun setConnState(address: String, state: ConnState) {
        _connections.value = _connections.value.toMutableMap().apply { put(address, state) }
    }

    private fun upsertPeer(peer: BlePeer) {
        peerMap[peer.deviceAddress] = peer
        _peers.value = peerMap.values.sortedByDescending { it.lastSeenMs }
    }

    /** The app layer calls this after decoding a peer's IDENTITY payload. */
    fun setPeerIdentity(address: String, peerId: String, displayName: String?) {
        val existing = peerMap[address]
        if (existing == null) {
            // Inbound-only peer (connected to our server without being scanned).
            upsertPeer(
                BlePeer(
                    deviceAddress = address,
                    peerId = peerId,
                    displayName = displayName,
                    lastSeenMs = System.currentTimeMillis(),
                ),
            )
        } else {
            upsertPeer(existing.copy(peerId = peerId, displayName = displayName ?: existing.displayName))
        }
    }

    // ------------------------------------------- identity resolution (v1.1.1)

    private fun postIdentityStatus(address: String, phase: IdentityPhase, message: String) {
        val resolver = identityResolvers[address]
        _identityStatus.value = _identityStatus.value.toMutableMap().apply {
            put(
                address,
                IdentityLinkStatus(
                    phase = phase,
                    attempt = resolver?.state?.attempt ?: 0,
                    maxAttempts = resolver?.maxAttempts ?: IdentityResolver.DEFAULT_MAX_ATTEMPTS,
                    message = message,
                ),
            )
        }
        diagnosticsListener?.let { listener ->
            runCatching { listener("ble $address: $message") }
        }
    }

    private fun resolverFor(address: String): IdentityResolver =
        identityResolvers.getOrPut(address) { IdentityResolver() }

    // -- Serialized GATT operations: exactly one outstanding per link.

    private fun enqueueGattOp(address: String, op: GattOp) {
        synchronized(gattOpLock) {
            gattOpQueues.getOrPut(address) { ArrayDeque() }.addLast(op)
        }
        pumpGattOps(address)
    }

    private fun pumpGattOps(address: String) {
        val next: GattOp = synchronized(gattOpLock) {
            if (!gattOpInFlight.add(address)) return
            val queue = gattOpQueues[address]
            if (queue == null || queue.isEmpty()) {
                gattOpInFlight.remove(address)
                return
            }
            queue.removeFirst()
        }
        val gatt = clientGatts[address]
        if (gatt == null) {
            finishGattOp(address)
            return
        }
        try {
            when (next) {
                GattOp.WriteCccd -> startCccdWriteOp(address, gatt)
                GattOp.ReadIdentity -> startIdentityReadOp(address, gatt)
            }
        } catch (e: Exception) {
            // A radio call must never kill the callback thread; the op
            // is abandoned and (for reads) the resolver timeout/retry
            // machinery owns what happens next.
            finishGattOp(address)
        }
    }

    private fun finishGattOp(address: String) {
        synchronized(gattOpLock) { gattOpInFlight.remove(address) }
        pumpGattOps(address)
    }

    private fun clearGattOps(address: String) {
        synchronized(gattOpLock) {
            gattOpQueues.remove(address)
            gattOpInFlight.remove(address)
        }
        readBusyRetries.remove(address)
    }

    @SuppressLint("MissingPermission")
    private fun startCccdWriteOp(address: String, gatt: BluetoothGatt) {
        val rx = gatt.getService(BleUuids.SERVICE_UUID)
            ?.getCharacteristic(BleUuids.RX_CHAR_UUID)
        val cccd = rx?.getDescriptor(BleUuids.CCCD_UUID)
        if (rx == null || cccd == null || !gatt.setCharacteristicNotification(rx, true)) {
            finishGattOp(address)
            return
        }
        try {
            writeDescriptorCompat(gatt, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } catch (e: SecurityException) {
            finishGattOp(address)
        }
        // Completion arrives in onDescriptorWrite -> finishGattOp.
    }

    @SuppressLint("MissingPermission")
    private fun startIdentityReadOp(address: String, gatt: BluetoothGatt) {
        val identity = gatt.getService(BleUuids.SERVICE_UUID)
            ?.getCharacteristic(BleUuids.IDENTITY_CHAR_UUID)
        if (identity == null) {
            onIdentityReadFailed(address, null, "this device does not offer an identity")
            finishGattOp(address)
            return
        }
        val resolver = resolverFor(address)
        resolver.onReadIssued(System.currentTimeMillis())
        scheduleIdentityTimeout(address)
        postIdentityStatus(
            address,
            IdentityPhase.RESOLVING,
            "Connected — finishing the connection (try ${resolver.state.attempt}/${resolver.maxAttempts})…",
        )
        val issued = try {
            gatt.readCharacteristic(identity)
        } catch (e: SecurityException) {
            false
        }
        if (!issued) {
            // The stack refused the operation — almost always because
            // another operation (e.g. a chunked send) is momentarily in
            // flight. Re-queue a few times WITHOUT burning the bounded
            // resolver attempt; only a persistent refusal fails it.
            cancelIdentityTimeout(address)
            val retries = (readBusyRetries[address] ?: 0) + 1
            if (retries <= MAX_READ_BUSY_RETRIES) {
                readBusyRetries[address] = retries
                synchronized(gattOpLock) {
                    gattOpQueues.getOrPut(address) { ArrayDeque() }
                        .addFirst(GattOp.ReadIdentity)
                    gattOpInFlight.remove(address)
                }
                handler.postDelayed({ pumpGattOps(address) }, READ_BUSY_RETRY_DELAY_MS)
            } else {
                readBusyRetries.remove(address)
                finishGattOp(address)
                onIdentityReadFailed(address, null, "the Bluetooth stack refused the identity read")
            }
        } else {
            readBusyRetries.remove(address)
        }
        // Completion arrives in onCharacteristicRead -> finishGattOp.
    }

    // -- Resolver driving.

    /** Called from onServicesDiscovered: begin (or resume, after a
     * resolver-driven link reset) the bounded identity exchange and
     * queue the read behind any CCCD write on this link. */
    private fun startResolutionAtDiscovery(address: String) {
        // A fresh link's discovery completes the handoff of any
        // resolver-driven reset: the parked session resumes below.
        resolutionResetsInFlight.remove(address)
        val resolver = resolverFor(address)
        if (resolver.state.phase != IdentityResolver.Phase.IN_FLIGHT) {
            resolver.beginSession(System.currentTimeMillis())
        }
        // Arm the watchdog NOW, not only when the read is issued: if
        // the CCCD write ahead of the read wedges, the session deadline
        // (armed by beginSession) still fails the attempt and the
        // retry machinery takes over instead of hanging.
        scheduleIdentityTimeout(address)
        enqueueGattOp(address, GattOp.ReadIdentity)
    }

    /** Self-perpetuating watchdog for one resolution session: consults
     * the resolver's deadline every second while a session is in
     * flight (the deadline is re-armed at every read issue, so a
     * single-shot timer could be outlived). Cancellation via
     * [cancelIdentityTimeout] breaks the chain. */
    private fun scheduleIdentityTimeout(address: String) {
        cancelIdentityTimeout(address)
        lateinit var runnable: Runnable
        runnable = Runnable {
            if (identityTimeouts[address] !== runnable) return@Runnable // superseded
            val resolver = identityResolvers[address]
            if (resolver == null ||
                resolver.state.phase != IdentityResolver.Phase.IN_FLIGHT
            ) {
                identityTimeouts.remove(address)
                return@Runnable
            }
            val action = resolver.onTick(System.currentTimeMillis())
            if (action != IdentityResolver.Action.NONE) {
                identityTimeouts.remove(address)
                // The stack has lost this operation — free the slot
                // so the retry can actually be issued.
                synchronized(gattOpLock) { gattOpInFlight.remove(address) }
                actOnResolutionAction(address, action, "timeout")
            } else {
                handler.postDelayed(runnable, WATCHDOG_INTERVAL_MS)
            }
        }
        identityTimeouts[address] = runnable
        handler.postDelayed(runnable, WATCHDOG_INTERVAL_MS)
    }

    private fun cancelIdentityTimeout(address: String) {
        identityTimeouts.remove(address)?.let { handler.removeCallbacks(it) }
    }

    /** Internal resolver failure reasons → words for the user. The
     *  technical originals stay in diagnostics; status lines shown in
     *  the main flow never say "identity", "GATT" or "payload". */
    private fun plainFailureReason(reason: String?): String = when {
        reason == null -> "something went wrong"
        reason == "timeout" -> "the other phone didn't answer in time"
        reason == "this device does not offer an identity" ->
            "the other phone didn't introduce itself"
        reason == "the Bluetooth stack refused the identity read" ->
            "Bluetooth refused a step"
        reason == "the identity read came back empty" ->
            "the other phone didn't answer properly"
        reason.startsWith("GATT status") -> "Bluetooth reported an error"
        else -> reason
    }

    private fun onIdentityReadFailed(address: String, gattStatus: Int?, reason: String) {
        cancelIdentityTimeout(address)
        val resolver = identityResolvers[address] ?: return
        if (resolver.isResolved) return
        val action = resolver.onReadFailed(System.currentTimeMillis(), gattStatus, reason)
        actOnResolutionAction(address, action, reason)
    }

    private fun actOnResolutionAction(
        address: String,
        action: IdentityResolver.Action,
        failureReason: String?,
    ) {
        val resolver = identityResolvers[address] ?: return
        when (action) {
            IdentityResolver.Action.NONE -> Unit
            IdentityResolver.Action.ISSUE_READ -> {
                postIdentityStatus(
                    address,
                    IdentityPhase.RESOLVING,
                    "Couldn't finish connecting (${plainFailureReason(failureReason)}) — " +
                        "trying again ${resolver.state.attempt}/${resolver.maxAttempts}…",
                )
                enqueueGattOp(address, GattOp.ReadIdentity)
            }
            IdentityResolver.Action.RESET_LINK_THEN_READ -> {
                postIdentityStatus(
                    address,
                    IdentityPhase.RESOLVING,
                    "Couldn't finish connecting (${plainFailureReason(failureReason)}) — " +
                        "the connection looks stuck, starting it again…",
                )
                resolver.onLinkResetForRetry()
                // A bare re-read on a possibly wedged connection is not
                // enough (field-confirmed): tear BOTH roles down and
                // reconnect from scratch. The resolver session (and its
                // attempt budget) survives; discovery on the fresh link
                // re-issues the read.
                resolutionResetsInFlight.add(address)
                teardownAllLinks(address)
                connect(address)
            }
            IdentityResolver.Action.GIVE_UP -> {
                val state = resolver.state
                val statusNote = state.lastGattStatus?.let { " (error $it)" } ?: ""
                postIdentityStatus(
                    address,
                    IdentityPhase.FAILED,
                    "Couldn't connect to this phone — " +
                        "${plainFailureReason(state.lastFailure)}$statusNote. Tap Try again.",
                )
            }
        }
    }

    /** The peer's identity payload arrived — by the GATT read OR by the
     * data-channel announcement; both are the same proof. */
    private fun onIdentityResolved(address: String) {
        cancelIdentityTimeout(address)
        val resolver = identityResolvers[address]
        if (resolver != null && !resolver.isResolved) {
            resolver.onResolved()
        }
        postIdentityStatus(address, IdentityPhase.RESOLVED, "Connected — ready to chat")
    }

    /**
     * Announce OUR identity payload over the link's data channel. The
     * GATT IDENTITY read only ever flows central→peripheral, so on its
     * own the peripheral side of a link can never learn who connected
     * to it; the announcement (JSON, demuxed by its leading '{' in
     * [handleIncomingChunk] — mesh envelopes start with the "P2P1"
     * magic and can never collide) makes resolution role-independent,
     * which is what lets the anti-collision yield keep exactly one link
     * without stranding either side's identity.
     */
    private fun announceIdentity(address: String) {
        if (!identityAnnounced.add(address)) return
        val payload = try {
            identityPayloadProvider()
        } catch (e: Exception) {
            ByteArray(0)
        }
        if (payload.isEmpty()) {
            identityAnnounced.remove(address)
            return
        }
        scope.launch {
            val sent = runCatching { send(address, payload) }.getOrDefault(false)
            if (!sent) identityAnnounced.remove(address) // retry on next READY
        }
    }

    // -- Anti-collision: exactly one central link per pair survives.

    private fun localAdapterAddress(): String? = try {
        adapter?.address
    } catch (e: Exception) {
        null
    }

    /**
     * If both link directions to [address] exist (both phones tapped
     * toward each other — the confirmed v1.1.0 field trigger), the
     * higher-address device yields: its central connection is torn down
     * cleanly (GATT closed, pending operations cancelled) and the pair
     * keeps the other direction only. Deterministic — both sides
     * compute the same survivor from the same two addresses.
     */
    private fun checkCollisionYield(address: String) {
        val yield = GattTieBreak.shouldYieldCentral(
            localAddress = localAdapterAddress(),
            remoteAddress = address,
            hasCentralLink = clientGatts.containsKey(address),
            hasPeripheralLink = serverDevices.containsKey(address),
        )
        if (!yield) return
        diagnosticsListener?.let { listener ->
            runCatching {
                listener("ble $address: two links collided — this phone stepped back to one link")
            }
        }
        cancelConnectTimeout(address)
        cancelIdentityTimeout(address)
        clearGattOps(address)
        identityResolvers[address]?.reset()
        identityAnnounced.remove(address)
        try {
            clientGatts.remove(address)?.let { gatt ->
                gatt.disconnect()
                gatt.close()
            }
        } catch (e: Exception) {
            // Already gone.
        }
        // The surviving inbound link carries the pair now; reflect it.
        // Our identity reaches the peer by announcement over this link,
        // and theirs reaches us the same way — no GATT read needed here.
        postIdentityStatus(
            address,
            IdentityPhase.RESOLVING,
            "Both phones connected at once — keeping one connection…",
        )
        if (subscribedServerPeers.contains(address)) {
            setConnState(address, ConnState.READY)
            announceIdentity(address)
        } else {
            setConnState(address, ConnState.CONNECTED)
        }
    }

    // -- Link teardown.

    /** Clear per-link runtime state (operation queue, timers,
     * reassembly) without touching the radio links themselves — and
     * deliberately WITHOUT touching the identity resolver: the
     * resolver-driven reset keeps its session and attempt budget across
     * the reset (that is what bounds the whole exchange). Callers that
     * end the session for good (manual disconnect, user retry) remove
     * the resolver explicitly. */
    private fun clearLinkRuntime(address: String) {
        cancelIdentityTimeout(address)
        clearGattOps(address)
        identityAnnounced.remove(address)
        reassemblers.remove(address)
        mtuByAddress.remove(address)
    }

    /** Tear down BOTH GATT roles for [address] and clear runtime state.
     * Used by the resolver's reset escalation and by [resetLink]. */
    @SuppressLint("MissingPermission")
    private fun teardownAllLinks(address: String) {
        cancelConnectTimeout(address)
        clearLinkRuntime(address)
        try {
            clientGatts.remove(address)?.let { gatt ->
                gatt.disconnect()
                gatt.close()
            }
        } catch (e: Exception) {
            // Already gone.
        }
        try {
            serverDevices.remove(address)?.let { device -> gattServer?.cancelConnection(device) }
        } catch (e: Exception) {
            // Server already gone.
        }
        subscribedServerPeers.remove(address)
    }

    /**
     * Full reset of one peer's link, then a fresh connect from scratch —
     * the retry the UI offers after a failed identity exchange, and the
     * only honest cure for a wedged GATT state: disconnect + close,
     * pending operations cleared, resolution budget restored.
     */
    @SuppressLint("MissingPermission")
    fun resetLink(address: String) {
        diagnosticsListener?.let { listener ->
            runCatching { listener("ble $address: full link reset — reconnecting from scratch") }
        }
        manualDisconnects.remove(address)
        teardownAllLinks(address)
        resolutionResetsInFlight.remove(address)
        identityResolvers.remove(address) // user retry = fresh attempt budget
        setConnState(address, ConnState.DISCONNECTED)
        connect(address)
    }

    // -------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val serviceData = result.scanRecord
                ?.getServiceData(ParcelUuid(BleUuids.SERVICE_UUID))
            // Name read is best-effort; scan permission was checked in startScanning().
            val name = try {
                result.scanRecord?.deviceName ?: if (BlePermissions.hasConnect(appContext)) device.name else null
            } catch (e: SecurityException) {
                result.scanRecord?.deviceName
            }
            val existing = peerMap[address]
            upsertPeer(
                BlePeer(
                    deviceAddress = address,
                    peerId = existing?.peerId,
                    displayName = name ?: existing?.displayName,
                    rssi = result.rssi,
                    lastSeenMs = System.currentTimeMillis(),
                    connectable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        result.isConnectable
                    } else {
                        true
                    },
                    identityHint = serviceData?.copyOf() ?: existing?.identityHint,
                ),
            )
        }

        override fun onScanFailed(errorCode: Int) {
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning(): Boolean {
        if (!isBluetoothEnabled()) return false
        if (!BlePermissions.hasScan(appContext)) return false
        val leScanner = adapter?.bluetoothLeScanner ?: return false
        if (_isScanning.value) return true
        scanner = leScanner
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        try {
            if (useUnfilteredFallback) {
                leScanner.startScan(null, settings, scanCallback)
            } else {
                val filter = ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BleUuids.SERVICE_UUID))
                    .build()
                leScanner.startScan(listOf(filter), settings, scanCallback)
            }
        } catch (e: SecurityException) {
            return false
        }
        _isScanning.value = true
        return true
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (_isScanning.value) {
            try {
                scanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                // Scanner already gone (BT off) — state flag below is the truth we keep.
            }
        }
        _isScanning.value = false
    }

    // ----------------------------------------------------------- advertising

    private var advertiseIncludeServiceData = true

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _isAdvertising.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            _isAdvertising.value = false
            // 128-bit UUID + service data can exceed the legacy 31-byte
            // advertisement on some devices: retry once without service data
            // (peer identity is then resolved only after connecting).
            if (errorCode == ADVERTISE_FAILED_DATA_TOO_LARGE && advertiseIncludeServiceData) {
                advertiseIncludeServiceData = false
                handler.post { startAdvertising() }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(): Boolean {
        if (!isBluetoothEnabled()) return false
        if (!BlePermissions.hasAdvertise(appContext)) return false
        if (!BlePermissions.hasConnect(appContext)) return false
        val leAdvertiser = adapter?.bluetoothLeAdvertiser ?: return false
        startGattServer() // peers connecting to us must find our service
        if (_isAdvertising.value) return true
        advertiser = leAdvertiser
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val dataBuilder = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(BleUuids.SERVICE_UUID))
            .setIncludeDeviceName(false)
        if (advertiseIncludeServiceData) {
            dataBuilder.addServiceData(
                ParcelUuid(BleUuids.SERVICE_UUID),
                identityHint(localPeerId),
            )
        }
        return try {
            leAdvertiser.startAdvertising(settings, dataBuilder.build(), advertiseCallback)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            // Advertiser gone with BT off.
        }
        _isAdvertising.value = false
    }

    // ------------------------------------------------------------ GATT server

    @SuppressLint("MissingPermission")
    fun startGattServer(): Boolean {
        if (gattServer != null) return true
        if (!BlePermissions.hasConnect(appContext)) return false
        val manager = bluetoothManager ?: return false
        val server = try {
            manager.openGattServer(appContext, gattServerCallback)
        } catch (e: SecurityException) {
            return false
        } ?: return false

        val tx = BluetoothGattCharacteristic(
            BleUuids.TX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val rx = BluetoothGattCharacteristic(
            BleUuids.RX_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val cccd = BluetoothGattDescriptor(
            BleUuids.CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        rx.addDescriptor(cccd)
        val identity = BluetoothGattCharacteristic(
            BleUuids.IDENTITY_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        val service = BluetoothGattService(BleUuids.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        service.addCharacteristic(identity)
        serverRxCharacteristic = rx
        gattServer = server
        try {
            server.addService(service)
        } catch (e: SecurityException) {
            return false
        }
        return true
    }

    fun stopGattServer() {
        try {
            gattServer?.close()
        } catch (e: Exception) {
            // Already closed.
        }
        gattServer = null
        serverRxCharacteristic = null
        serverDevices.clear()
        subscribedServerPeers.clear()
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address ?: return
            scope.launch {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    knownAddresses.add(address)
                    serverDevices[address] = device
                    if (_connections.value[address] == null ||
                        _connections.value[address] == ConnState.DISCONNECTED
                    ) {
                        setConnState(address, ConnState.CONNECTED)
                    }
                    // If we are simultaneously connecting OUT to this
                    // same device, the tie-break may retire our link.
                    checkCollisionYield(address)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    serverDevices.remove(address)
                    subscribedServerPeers.remove(address)
                    reassemblers.remove(address)
                    if (!clientGatts.containsKey(address)) {
                        setConnState(address, ConnState.DISCONNECTED)
                        maybeReconnect(address)
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            mtuByAddress[device.address] = mtu
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val server = gattServer ?: return
            if (characteristic.uuid == BleUuids.IDENTITY_CHAR_UUID) {
                val payload = try {
                    identityPayloadProvider()
                } catch (e: Exception) {
                    ByteArray(0)
                }
                val slice = if (offset < payload.size) {
                    payload.copyOfRange(offset, payload.size)
                } else {
                    ByteArray(0)
                }
                respond(server, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            } else {
                respond(server, device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            val server = gattServer
            if (characteristic.uuid == BleUuids.TX_CHAR_UUID && value != null) {
                handleIncomingChunk(device.address, value)
                if (responseNeeded && server != null) {
                    respond(server, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            } else if (responseNeeded && server != null) {
                respond(server, device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (descriptor.uuid == BleUuids.CCCD_UUID) {
                val enabled = value != null &&
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enabled) {
                    subscribedServerPeers.add(device.address)
                    if (_connections.value[device.address] != ConnState.READY) {
                        setConnState(device.address, ConnState.READY)
                    }
                    // This peer is our client on this link: it can read
                    // our identity itself, but announce anyway — cheap,
                    // and it resolves us on stacks where reads stall.
                    announceIdentity(device.address)
                } else {
                    subscribedServerPeers.remove(device.address)
                }
            }
            if (responseNeeded) {
                gattServer?.let {
                    respond(it, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            val value = if (subscribedServerPeers.contains(device.address)) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            }
            gattServer?.let { respond(it, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun respond(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?,
    ) {
        try {
            server.sendResponse(device, requestId, status, offset, value)
        } catch (e: SecurityException) {
            // Peer vanished mid-request.
        }
    }

    // ------------------------------------------------------------ GATT client

    @SuppressLint("MissingPermission")
    fun connect(address: String): Boolean {
        if (!isBluetoothEnabled()) return false
        if (!BlePermissions.hasConnect(appContext)) return false
        // Anti-collision pre-check: the peer has already connected TO
        // us and, by the deterministic address tie-break, this phone is
        // the pair's listener — a second, opposite link is exactly the
        // simultaneous-initiate collision, so don't create it. The
        // identity exchange proceeds over the inbound link (the peer
        // reads ours; theirs arrives by announcement).
        if (serverDevices.containsKey(address) &&
            GattTieBreak.shouldYieldCentral(
                localAddress = localAdapterAddress(),
                remoteAddress = address,
                hasCentralLink = true,
                hasPeripheralLink = true,
            )
        ) {
            postIdentityStatus(
                address,
                IdentityPhase.RESOLVING,
                "The other phone is connecting to us…",
            )
            return true
        }
        val current = _connections.value[address]
        if (current == ConnState.CONNECTING || current == ConnState.CONNECTED ||
            current == ConnState.DISCOVERING || current == ConnState.READY
        ) {
            return true
        }
        if (activeConnectionCount() >= MAX_CONNECTIONS) return false
        val device = try {
            adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        } ?: return false
        manualDisconnects.remove(address)
        knownAddresses.add(address)
        setConnState(address, ConnState.CONNECTING)
        val gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(appContext, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(appContext, false, gattClientCallback)
            }
        } catch (e: SecurityException) {
            setConnState(address, ConnState.FAILED)
            return false
        }
        if (gatt == null) {
            setConnState(address, ConnState.FAILED)
            return false
        }
        clientGatts[address] = gatt
        postIdentityStatus(address, IdentityPhase.RESOLVING, "Connecting to the other phone…")
        scheduleConnectTimeout(address)
        // The peer may have connected to us in the same instant —
        // re-check the collision now that our central link exists.
        checkCollisionYield(address)
        return true
    }

    @SuppressLint("MissingPermission")
    fun disconnect(address: String) {
        manualDisconnects.add(address)
        cancelConnectTimeout(address)
        reconnectAttempts.remove(address)
        resolutionResetsInFlight.remove(address)
        clearLinkRuntime(address)
        identityResolvers.remove(address)
        try {
            clientGatts.remove(address)?.let { gatt ->
                gatt.disconnect()
                gatt.close()
            }
        } catch (e: Exception) {
            // Link already gone.
        }
        try {
            serverDevices[address]?.let { device -> gattServer?.cancelConnection(device) }
        } catch (e: Exception) {
            // Server already gone.
        }
        serverDevices.remove(address)
        subscribedServerPeers.remove(address)
        reassemblers.remove(address)
        setConnState(address, ConnState.DISCONNECTED)
    }

    private fun scheduleConnectTimeout(address: String) {
        cancelConnectTimeout(address)
        val runnable = Runnable {
            val state = _connections.value[address]
            if (state == ConnState.CONNECTING || state == ConnState.CONNECTED ||
                state == ConnState.DISCOVERING
            ) {
                postIdentityStatus(
                    address,
                    IdentityPhase.FAILED,
                    "The other phone didn't answer — tap Try again.",
                )
                setConnState(address, ConnState.FAILED)
                disconnectInternal(address, manual = false)
                maybeReconnect(address)
            }
        }
        connectTimeouts[address] = runnable
        handler.postDelayed(runnable, CONNECT_TIMEOUT_MS)
    }

    private fun cancelConnectTimeout(address: String) {
        connectTimeouts.remove(address)?.let { handler.removeCallbacks(it) }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectInternal(address: String, manual: Boolean) {
        if (manual) manualDisconnects.add(address)
        cancelConnectTimeout(address)
        clearLinkRuntime(address)
        identityResolvers.remove(address)
        try {
            clientGatts.remove(address)?.let { it.disconnect(); it.close() }
        } catch (e: Exception) {
            // Already gone.
        }
    }

    private fun maybeReconnect(address: String) {
        if (!autoReconnect) return
        if (manualDisconnects.contains(address)) return
        if (!knownAddresses.contains(address)) return
        if (!isBluetoothEnabled()) return
        val attempts = (reconnectAttempts[address] ?: 0) + 1
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            reconnectAttempts.remove(address)
            return
        }
        reconnectAttempts[address] = attempts
        val backoffMs = (1L shl (attempts - 1)) * 1_000L // 1s, 2s, 4s
        handler.postDelayed({
            if (!manualDisconnects.contains(address) && isBluetoothEnabled()) {
                connect(address)
            }
        }, backoffMs)
    }

    private val gattClientCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device?.address ?: return
            // Stale-GATT guard: after a link reset, callbacks from the
            // torn-down GATT object must not hijack the fresh link's
            // state. If a different GATT is registered for this address,
            // this event belongs to the dead one.
            val registered = clientGatts[address]
            if (registered != null && registered !== gatt) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    try {
                        gatt.close()
                    } catch (e: Exception) {
                        // Already closed.
                    }
                }
                return
            }
            scope.launch {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    setConnState(address, ConnState.CONNECTED)
                    // Both phones may have initiated at once; the
                    // designated-central rule may retire this very link.
                    checkCollisionYield(address)
                    if (clientGatts[address] !== gatt) return@launch // yielded
                    try {
                        gatt.requestMtu(REQUESTED_MTU)
                    } catch (e: Exception) {
                        // MTU stays at default for this link.
                    }
                    setConnState(address, ConnState.DISCOVERING)
                    try {
                        gatt.discoverServices()
                    } catch (e: SecurityException) {
                        setConnState(address, ConnState.FAILED)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    cancelConnectTimeout(address)
                    try {
                        gatt.close()
                    } catch (e: Exception) {
                        // Already closed.
                    }
                    if (clientGatts[address] === gatt) {
                        clientGatts.remove(address)
                    }
                    clearLinkRuntime(address)
                    if (resolutionResetsInFlight.contains(address)) {
                        // A resolver-driven reset owns this teardown:
                        // the session (and its budget) survives, and
                        // the reset's own connect() — not the generic
                        // auto-reconnect — drives the fresh link.
                    } else {
                        identityResolvers.remove(address)
                        if (!serverDevices.containsKey(address)) {
                            setConnState(address, ConnState.DISCONNECTED)
                            maybeReconnect(address)
                        }
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mtuByAddress[gatt.device.address] = mtu
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val address = gatt.device?.address ?: return
            if (clientGatts[address] !== gatt) return // stale link's discovery
            if (status != BluetoothGatt.GATT_SUCCESS) {
                postIdentityStatus(
                    address,
                    IdentityPhase.FAILED,
                    "Couldn't finish connecting (error $status) — tap Try again.",
                )
                setConnState(address, ConnState.FAILED)
                return
            }
            val service = gatt.getService(BleUuids.SERVICE_UUID)
            if (service == null) {
                // Not a P2P Chat peer — do not pretend otherwise.
                setConnState(address, ConnState.FAILED)
                disconnect(address)
                return
            }
            // STRICTLY SERIALIZED OPERATIONS (the v1.1.0 hang): the
            // previous code issued the CCCD descriptor write and then
            // the IDENTITY read back-to-back; Android allows exactly
            // one outstanding GATT operation, so the read was silently
            // dropped, no callback ever arrived, and with no timeout or
            // retry the identity never resolved. Both operations now go
            // through the per-link queue: the read is issued only when
            // the descriptor write completes, and the resolver bounds
            // the whole exchange with timeouts + retries.
            val rx = service.getCharacteristic(BleUuids.RX_CHAR_UUID)
            val cccd = rx?.getDescriptor(BleUuids.CCCD_UUID)
            if (rx != null && cccd != null) {
                enqueueGattOp(address, GattOp.WriteCccd)
            } else if (service.getCharacteristic(BleUuids.TX_CHAR_UUID) != null) {
                // No notify path — writes still work one-way; READY on
                // the strength of the TX characteristic alone.
                markReady(address)
            } else {
                setConnState(address, ConnState.FAILED)
            }
            if (service.getCharacteristic(BleUuids.IDENTITY_CHAR_UUID) != null) {
                startResolutionAtDiscovery(address)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val address = gatt.device?.address ?: return
            if (clientGatts[address] !== gatt) return // stale link's callback
            // Free the operation slot first so the queued identity
            // read is issued immediately.
            finishGattOp(address)
            if (descriptor.uuid == BleUuids.CCCD_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                markReady(address)
            }
        }

        // API 33+ read callback with value.
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            onIdentityReadResult(gatt, characteristic, value, status)
        }

        // Pre-API 33 read callback.
        @Deprecated("Deprecated in API 33; both overloads are required for minSdk 26 support")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            onIdentityReadResult(gatt, characteristic, value, status)
        }

        // API 33+ change callback with value.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (characteristic.uuid == BleUuids.RX_CHAR_UUID) {
                handleIncomingChunk(gatt.device.address, value)
            }
        }

        // Pre-API 33 change callback.
        @Deprecated("Deprecated in API 33; both overloads are required for minSdk 26 support")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid == BleUuids.RX_CHAR_UUID) {
                @Suppress("DEPRECATION")
                val value = characteristic.value ?: return
                handleIncomingChunk(gatt.device.address, value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val address = gatt.device?.address ?: return
            writeAckWaiters.remove(address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /** Single funnel for IDENTITY read completions (both callback
     * shapes): frees the operation slot, then routes success to the
     * payload handler and any failure — including transient GATT
     * statuses like 133 — into the bounded resolver retry loop. */
    private fun onIdentityReadResult(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        if (characteristic.uuid != BleUuids.IDENTITY_CHAR_UUID) return
        val address = gatt.device?.address ?: return
        if (clientGatts[address] !== gatt) return // stale link's callback
        finishGattOp(address)
        if (status == BluetoothGatt.GATT_SUCCESS && value.isNotEmpty()) {
            handleIdentityPayload(address, value)
        } else if (status == BluetoothGatt.GATT_SUCCESS) {
            onIdentityReadFailed(address, null, "the identity read came back empty")
        } else {
            onIdentityReadFailed(address, status, "GATT status $status")
        }
    }

    private fun markReady(address: String) {
        cancelConnectTimeout(address)
        reconnectAttempts.remove(address)
        setConnState(address, ConnState.READY)
        // The peer may be the peripheral on this link and unable to
        // read our identity — announce it over the data channel.
        announceIdentity(address)
    }

    private fun handleIdentityPayload(address: String, payload: ByteArray) {
        if (payload.isEmpty()) return
        onIdentityResolved(address)
        _peerIdentities.value = _peerIdentities.value.toMutableMap().apply { put(address, payload.copyOf()) }
        // Best-effort convenience parse: payloads that begin with a printable
        // token (peerId, optionally "|" + display name) resolve immediately.
        // Anything else is left for the app layer via [peerIdentities].
        val text = payload.toString(Charsets.UTF_8)
        val first = text.substringBefore('|').substringBefore('\n').trim()
        if (first.length in 8..64 && first.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            val name = text.substringAfter('|', "").substringBefore('\n').trim()
                .takeIf { it.isNotEmpty() }
            setPeerIdentity(address, first, name)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        writeType: Int,
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, value, writeType) == statusSuccess
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    } catch (e: SecurityException) {
        false
    }

    /** BluetoothStatusCodes.SUCCESS (API 33+ return value of write/notify calls). */
    private val statusSuccess: Int = 0

    // ------------------------------------------------------------ data path

    private fun handleIncomingChunk(address: String, chunkBytes: ByteArray) {
        val chunk = try {
            PacketChunker.decode(chunkBytes)
        } catch (e: Exception) {
            return // malformed chunk — drop, never crash the radio callback
        }
        val reassembler = reassemblers.getOrPut(address) { ChunkReassembler(REASSEMBLY_TIMEOUT_MS) }
        val complete = try {
            reassembler.add(chunk)
        } catch (e: Exception) {
            null
        } ?: return
        // Identity announcement demux: a JSON payload (leading '{') on
        // the data channel is the peer announcing who it is — the
        // role-independent identity path. Mesh envelopes begin with the
        // "P2P1" codec magic, so this can never swallow a real message.
        if (complete.isNotEmpty() && complete[0] == '{'.code.toByte()) {
            handleIdentityPayload(address, complete)
            return
        }
        // Connection-speed check frames ("P2PT" magic — cannot collide
        // with the "P2P1" mesh magic or the '{' identity JSON): consumed
        // here, never forwarded to the mesh layer.
        if (LinkTestFrames.isTestFrame(complete)) {
            handleLinkTestFrame(address, complete)
            return
        }
        scope.launch {
            _incoming.emit(IncomingData(address, complete))
        }
    }

    /** A speed-check frame arrived: answer pings at once, complete the
     *  waiting pong for our own measurement, swallow data frames (their
     *  arrival IS the measurement — they are not chat traffic). */
    private fun handleLinkTestFrame(address: String, bytes: ByteArray) {
        val frame = LinkTestFrames.parse(bytes) ?: return
        when (frame.type) {
            LinkTestFrames.TYPE_PING -> {
                val pong = LinkTestFrames.pongOf(bytes) ?: return
                scope.launch { runCatching { send(address, pong) } }
            }
            LinkTestFrames.TYPE_PONG -> {
                pongWaiters.remove(frame.seq)?.complete(System.nanoTime())
            }
            else -> Unit // TYPE_DATA: measurement payload, nothing to do
        }
    }

    /**
     * Measure the real speed of the link to [address] (master spec §7):
     * five ping/pong round trips, then a burst of data frames pushed as
     * fast as the channel accepts them. Returns null when there is no
     * usable link, another test is already running, or not a single
     * ping came back — the UI shows an honest "couldn't measure" state
     * instead of invented numbers.
     */
    suspend fun runLinkTest(address: String): LinkTestResult? {
        if (!isReady(address) && !serverDevices.containsKey(address)) return null
        if (!linkTestInFlight.compareAndSet(false, true)) return null
        try {
            val rttSamplesMs = mutableListOf<Long>()
            repeat(LINK_TEST_PINGS) {
                val seq = linkTestSeq.incrementAndGet()
                val waiter = kotlinx.coroutines.CompletableDeferred<Long>()
                pongWaiters[seq] = waiter
                val sentAtNs = System.nanoTime()
                val sent = send(address, LinkTestFrames.encode(LinkTestFrames.TYPE_PING, seq, LINK_TEST_PING_BYTES))
                if (sent) {
                    val answeredAtNs = withTimeoutOrNull(LINK_TEST_PONG_TIMEOUT_MS) { waiter.await() }
                    if (answeredAtNs != null) {
                        rttSamplesMs += (answeredAtNs - sentAtNs) / 1_000_000L
                    }
                }
                pongWaiters.remove(seq)
            }
            if (rttSamplesMs.isEmpty()) return null
            var sentBytes = 0L
            val burstStartNs = System.nanoTime()
            for (i in 0 until LINK_TEST_BURST_FRAMES) {
                val seq = linkTestSeq.incrementAndGet()
                val ok = send(address, LinkTestFrames.encode(LinkTestFrames.TYPE_DATA, seq, LINK_TEST_FRAME_BYTES))
                if (!ok) break
                sentBytes += LINK_TEST_FRAME_BYTES
            }
            val durationMs = (System.nanoTime() - burstStartNs) / 1_000_000L
            if (sentBytes == 0L) return null
            return LinkTestResult(
                address = address,
                totalBytes = sentBytes,
                durationMs = durationMs,
                rttSamplesMs = rttSamplesMs,
            )
        } finally {
            linkTestInFlight.set(false)
        }
    }

    /** Payload bytes per chunk for [address] from its negotiated MTU:
     * mtu - 3 (ATT overhead), defaulting to the un-negotiated 23-byte MTU
     * (= 20 payload bytes) and capped at [MAX_PAYLOAD_CAP]. */
    fun chunkPayloadFor(address: String): Int {
        val mtu = mtuByAddress[address] ?: DEFAULT_MTU
        return (mtu - 3).coerceIn(1, MAX_PAYLOAD_CAP)
    }

    fun isReady(address: String): Boolean = _connections.value[address] == ConnState.READY

    fun readyAddresses(): Set<String> =
        _connections.value.filterValues { it == ConnState.READY }.keys

    /** Address → resolved peerId, for links that are READY. Used by the mesh
     * layer to bridge BLE addresses and mesh peer identities. */
    fun readyPeerIds(): Map<String, String> =
        readyAddresses().mapNotNull { address ->
            peerMap[address]?.peerId?.let { address to it }
        }.toMap()

    /**
     * Send [data] to the device at [address], chunked for its MTU.
     * Returns true only if every chunk was accepted by the radio path.
     * Returns false when the link is not READY — callers (mesh outbox) keep
     * the message queued; a relay accepting bytes is never a delivery proof.
     */
    suspend fun send(address: String, data: ByteArray): Boolean {
        if (!isBluetoothEnabled()) return false
        if (!BlePermissions.hasConnect(appContext)) return false
        if (!isReady(address) && !serverDevices.containsKey(address)) return false
        val mutex = sendMutexByAddress.getOrPut(address) { Mutex() }
        return mutex.withLock {
            val transferId = transferIdCounter.incrementAndGet()
            val chunks = PacketChunker.split(data, chunkPayloadFor(address), transferId)
            val gatt = clientGatts[address]
            val txChar = gatt?.getService(BleUuids.SERVICE_UUID)
                ?.getCharacteristic(BleUuids.TX_CHAR_UUID)
            if (gatt != null && txChar != null) {
                sendViaClientLocked(address, gatt, txChar, chunks.map { PacketChunker.encode(it) })
            } else if (serverDevices.containsKey(address)) {
                sendViaServerNotifyLocked(address, chunks.map { PacketChunker.encode(it) })
            } else {
                false
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendViaClientLocked(
        address: String,
        gatt: BluetoothGatt,
        txChar: BluetoothGattCharacteristic,
        encodedChunks: List<ByteArray>,
    ): Boolean {
        val canAck = (txChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        for (bytes in encodedChunks) {
            if (canAck) {
                val waiter = kotlinx.coroutines.CompletableDeferred<Boolean>()
                writeAckWaiters[address] = waiter
                val initiated = writeCharacteristicCompat(
                    gatt, txChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
                if (!initiated) {
                    writeAckWaiters.remove(address)
                    return false
                }
                val ok = withTimeoutOrNull(3_000L) { waiter.await() } ?: false
                if (!ok) return false
            } else {
                val initiated = writeCharacteristicCompat(
                    gatt, txChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
                )
                if (!initiated) return false
                delay(15L) // pace no-response writes so the peer's stack keeps up
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun sendViaServerNotifyLocked(
        address: String,
        encodedChunks: List<ByteArray>,
    ): Boolean {
        val server = gattServer ?: return false
        val device = serverDevices[address] ?: return false
        val rx = serverRxCharacteristic ?: return false
        if (!subscribedServerPeers.contains(address)) return false
        for (bytes in encodedChunks) {
            val ok = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, rx, false, bytes) == statusSuccess
                } else {
                    @Suppress("DEPRECATION")
                    rx.value = bytes
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, rx, false)
                }
            } catch (e: SecurityException) {
                return false
            }
            if (!ok) return false
            delay(15L)
        }
        return true
    }

    // --------------------------------------------------------------- lifecycle

    /** Start everything the current permission/BT state allows. Each step is
     * independent: scanning may start while advertising is still blocked etc. */
    fun start() {
        startGattServer()
        startScanning()
        startAdvertising()
    }

    fun stop() {
        stopScanning()
        stopAdvertising()
        _connections.value.keys.toList().forEach { disconnect(it) }
        stopGattServer()
    }

    /** Release all resources. The transport must not be used after [close]. */
    fun close() {
        stop()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
    }
}
