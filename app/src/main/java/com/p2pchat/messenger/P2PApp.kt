package com.p2pchat.messenger

import android.app.Application
import android.content.Context
import android.util.Base64
import com.p2pchat.messenger.ble.BleTransport
import com.p2pchat.messenger.ble.model.ConnState
import com.p2pchat.messenger.core.call.CallRecordBuilder
import com.p2pchat.messenger.core.codec.EnvelopeCodec
import com.p2pchat.messenger.core.model.EnvelopeType
import com.p2pchat.messenger.core.model.MessageEnvelope
import com.p2pchat.messenger.core.model.PeerIds
import com.p2pchat.messenger.crypto.CryptoManager
import com.p2pchat.messenger.crypto.Identity
import com.p2pchat.messenger.data.IdentityStore
import com.p2pchat.messenger.data.P2PDatabase
import com.p2pchat.messenger.data.RoomOutboxStore
import com.p2pchat.messenger.data.dao.OutboxDao
import com.p2pchat.messenger.data.entity.OutboxEntity
import com.p2pchat.messenger.data.repository.CallHistoryRepository
import com.p2pchat.messenger.data.repository.ChatRepository
import com.p2pchat.messenger.data.repository.PeerRepository
import com.p2pchat.messenger.mesh.CompositeMeshTransport
import com.p2pchat.messenger.mesh.DeliveryState
import com.p2pchat.messenger.mesh.MeshEvent
import com.p2pchat.messenger.mesh.MeshRouter
import com.p2pchat.messenger.mesh.MeshTransport
import com.p2pchat.messenger.mesh.OutboxBackoff
import com.p2pchat.messenger.mesh.OutboxStore
import com.p2pchat.messenger.mesh.PeerInfo
import com.p2pchat.messenger.mesh.PeerTable
import com.p2pchat.messenger.mesh.QueuedMessage
import com.p2pchat.messenger.nearby.CallState
import com.p2pchat.messenger.nearby.NearbyCallController
import com.p2pchat.messenger.nearby.NearbyPermissions
import com.p2pchat.messenger.wifidirect.RtcEngine
import com.p2pchat.messenger.wifidirect.WifiCallController
import com.p2pchat.messenger.wifidirect.WifiCallState
import java.util.UUID
import com.p2pchat.messenger.nearby.NearbyTransport
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Application entry point. Owns the single [AppContainer] (manual DI —
 * no framework) so the foreground service, the activity and every screen
 * share one database, one identity, one BLE transport and one mesh router.
 */
class P2PApp : Application() {

    /** Null until [onCreate] has successfully built the container. */
    @Volatile
    var container: AppContainer? = null
        private set

    /** The startup failure, if [container] could not be built. */
    @Volatile
    var startupError: Throwable? = null
        private set

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // Diagnostic build: install the crash handler at the earliest
        // possible point — before the androidx.startup InitializationProvider
        // runs and before onCreate — so even a provider/class-loading crash
        // is recorded to a file MainActivity can show on the next launch.
        CrashReporter.install(base)
    }

    override fun onCreate() {
        super.onCreate()
        // NON-FATAL by design: if container construction dies, record the
        // failure and let the process live so MainActivity can surface the
        // report instead of the app crashing identically on every launch.
        try {
            val built = AppContainer(this)
            container = built
            CrashReporter.breadcrumb(this, "container-created")
            built.bootstrap()
            CrashReporter.breadcrumb(this, "bootstrap-done")
        } catch (t: Throwable) {
            startupError = t
            CrashReporter.recordThrowable(this, "P2PApp.onCreate", t)
        }
    }
}

/**
 * Manual dependency container.
 *
 * Wiring graph (built once an identity exists):
 *
 * ```
 * BleTransport ──incoming──▶ MeshRouter ──events──▶ ChatRepository ──▶ Room
 *      ▲                        │  ▲                       ▲
 *      └──── BleMeshTransport ──┘  └── RoomOutboxStore ────┘ (OutboxDao)
 * Identity payloads (peerIdentities flow) ──▶ PeerTable + PeerRepository
 * ```
 *
 * Identity lifecycle: [bootstrap] loads a stored identity if one exists;
 * [ensureIdentity] creates + persists a new one when the onboarding flow
 * supplies a display name. Transport/router/repositories are only created
 * after an identity is known, because the transport advertises the peerId
 * and the router signs/decrypts as that identity.
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val database: P2PDatabase = P2PDatabase.create(appContext)
    val crypto: CryptoManager = CryptoManager()
    val identityStore: IdentityStore = IdentityStore(appContext)

    val peerRepository: PeerRepository = PeerRepository(database)
    val callHistoryRepository: CallHistoryRepository = CallHistoryRepository(database)
    val peerTable: PeerTable = PeerTable()

    /** Room-backed store-and-forward queue handed to the mesh router. */
    val outboxStore: OutboxStore = RoomOutboxStore(database.outboxDao())

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _identity = MutableStateFlow<Identity?>(null)

    /** The local identity once loaded/created; null during first run. */
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    @Volatile
    private var _bleTransport: BleTransport? = null

    /** Non-null only after an identity exists. */
    val bleTransport: BleTransport? get() = _bleTransport

    @Volatile
    private var _router: MeshRouter? = null
    val router: MeshRouter? get() = _router

    @Volatile
    private var _nearbyTransport: NearbyTransport? = null

    /** Google Nearby Connections direct link; non-null after identity
     *  wiring. [NearbyTransport.available] is false when Play services is
     *  missing — the BLE mesh alone carries the app then. */
    val nearbyTransport: NearbyTransport? get() = _nearbyTransport

    @Volatile
    private var _callController: NearbyCallController? = null

    /** Experimental direct voice calls over Nearby; null pre-identity. */
    val callController: NearbyCallController? get() = _callController

    @Volatile
    private var _rtcEngine: RtcEngine? = null

    /** WebRTC engine for Wi-Fi Direct calls; null pre-identity. */
    val rtcEngine: RtcEngine? get() = _rtcEngine

    @Volatile
    private var _wifiCallController: WifiCallController? = null

    /** Offline voice/video calls over a Wi-Fi Direct group (WebRTC,
     *  no internet, no servers); null pre-identity. */
    val wifiCallController: WifiCallController? get() = _wifiCallController

    // -- Call feedback that cannot be missed (v1.3.1) -------------------
    // Field report on v1.3.0: tapping a call option appeared to do
    // NOTHING — no dialog, no error, no call screen. Whatever branch a
    // call tap takes, its outcome now lands here: a notice rendered as
    // a full dialog at the navigation root (not a small inline text),
    // and a timestamped line in the call diary shown under Connection
    // details, so a failure names its own branch on the device itself.

    private val _callNotice = MutableStateFlow<String?>(null)

    /** A user-facing call message to show as a dialog, or null. */
    val callNotice: StateFlow<String?> = _callNotice.asStateFlow()

    fun showCallNotice(message: String) {
        _callNotice.value = message
    }

    fun dismissCallNotice() {
        _callNotice.value = null
    }

    private val _callDebug = MutableStateFlow<List<String>>(emptyList())

    /** Timestamped call-attempt diary (last 25 events), shown in
     *  Connection details — the on-device evidence trail for call bugs. */
    val callDebug: StateFlow<List<String>> = _callDebug.asStateFlow()

    fun logCallEvent(event: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _callDebug.value = (_callDebug.value + "$stamp  $event").takeLast(25)
    }

    /** Directory holding recorded/received voice-note files. */
    val voiceDir: File = File(appContext.filesDir, "voice")

    @Volatile
    private var _chatRepository: ChatRepository? = null

    /** Non-null only after an identity exists. */
    val chatRepository: ChatRepository? get() = _chatRepository

    // BLE address <-> mesh peerId mapping, learned from IDENTITY reads.
    private val peerIdByAddress = ConcurrentHashMap<String, String>()
    private val addressByPeerId = ConcurrentHashMap<String, String>()

    // Nearby endpointId <-> mesh peerId mapping, learned from the identity
    // payload exchanged over each established Nearby connection. An
    // endpoint is usable for routing/calls ONLY once present here.
    private val peerIdByEndpoint = ConcurrentHashMap<String, String>()
    private val endpointByPeerId = ConcurrentHashMap<String, String>()

    /** Load a stored identity (if any) and wire the mesh stack around it. */
    fun bootstrap() {
        appScope.launch { ensureIdentity(null) }
    }

    /**
     * Returns the existing identity, loading it from [IdentityStore] on
     * first call. When none is stored, creates and persists a new identity
     * if [displayName] is non-blank; otherwise returns null (first run —
     * the onboarding screen must collect a display name).
     */
    suspend fun ensureIdentity(displayName: String?): Identity? = withContext(Dispatchers.IO) {
        _identity.value?.let { return@withContext it }
        identityStore.load(crypto)?.let { loaded ->
            wireIdentity(loaded)
            return@withContext loaded
        }
        val name = displayName?.trim().orEmpty()
        if (name.isEmpty()) return@withContext null
        val created = crypto.createIdentity(name)
        identityStore.save(created)
        wireIdentity(created)
        created
    }

    /** Rename the local identity. Keys (and therefore peerId) do not change,
     * so the already-wired router/transport stay valid; the identity
     * payload provider reads [_identity] live, so the new name is served
     * on the next IDENTITY characteristic read. */
    fun updateDisplayName(newName: String) {
        val current = _identity.value ?: return
        val name = newName.trim()
        if (name.isEmpty() || name == current.displayName) return
        val updated = Identity(
            peerId = current.peerId,
            displayName = name,
            ed25519Public = current.ed25519Public,
            x25519Public = current.x25519Public,
            ed25519Private = current.ed25519Private,
            x25519Private = current.x25519Private
        )
        identityStore.save(updated)
        _identity.value = updated
    }

    /** Hex fingerprint of the local Ed25519 public key, for out-of-band
     * identity verification between users. */
    fun fingerprintHex(identity: Identity): String =
        identity.ed25519Public.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun isBluetoothEnabled(): Boolean =
        _bleTransport?.isBluetoothEnabled()
            ?: (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)
                ?.adapter?.isEnabled == true

    /**
     * Wipe all local data: messages, conversations, outbox, seen-cache,
     * peers and the stored identity.
     *
     * NOTE: the already-constructed transport/router keep the old identity
     * in memory until the process restarts — the settings screen tells the
     * user to restart the app after clearing.
     */
    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        database.messageDao().clearAll()
        database.conversationDao().clearAll()
        database.outboxDao().clearAll()
        database.seenDao().clearAll()
        database.voiceTransferDao().clearAll()
        database.voiceChunkDao().clearAll()
        database.callRecordDao().clearAll()
        runCatching { voiceDir.deleteRecursively() }
        // TODO(contract): PeerDao has no clearAll(); delete row by row.
        peerRepository.peers.first().forEach { database.peerDao().deleteById(it.peerId) }
        peerTable.clear()
        peerIdByAddress.clear()
        addressByPeerId.clear()
        peerIdByEndpoint.clear()
        endpointByPeerId.clear()
        identityStore.clear()
        _identity.value = null
    }

    /**
     * Kick off every direct path to [peerId] without blocking the UI:
     * a BLE connect to the last known address and, when permissions allow,
     * a Nearby Connections session to the endpoint advertising this
     * peer's identity hint. The chat screen calls this on open; messages
     * queue in the outbox until a path + identity are ready.
     */
    fun startDirectLink(peerId: String) {
        val ble = _bleTransport
        val knownAddress = addressByPeerId[peerId]
            ?: peerTable.get(peerId)?.address?.takeUnless { it.startsWith(NEARBY_ADDRESS_PREFIX) }
        if (ble != null && knownAddress != null) {
            runCatching { ble.connect(knownAddress) }
        }
        val nearby = _nearbyTransport ?: return
        if (!nearby.available || !NearbyPermissions.hasAll(appContext)) return
        // Wanted: the transport now connects whenever discovery finds
        // this peer and retries failed attempts on its own — the old
        // one-shot lookup raced discovery and silently gave up, which
        // is why calls placed from a fresh app start never got through.
        nearby.wantPeer(peerId)
        nearby.start()
        nearby.endpointByHint(NearbyTransport.identityHint(peerId))?.let { endpoint ->
            nearby.connect(endpoint.endpointId)
        }
    }

    /** Start Nearby listening (advertising + discovery) when the
     *  permissions are in place. Called at wiring time and again after
     *  the user grants permissions, so this phone can RECEIVE messages
     *  and calls without first opening a chat (v1.2.1). */
    fun ensureNearbyStarted() {
        val nearby = _nearbyTransport ?: return
        if (!nearby.available || !NearbyPermissions.hasAll(appContext)) return
        runCatching { nearby.start() }
    }

    /** BLE address last associated with [peerId], if any (never a
     * Nearby pseudo-address). */
    fun bleAddressForPeer(peerId: String): String? =
        addressByPeerId[peerId]
            ?: peerTable.get(peerId)?.address?.takeUnless { it.startsWith(NEARBY_ADDRESS_PREFIX) }

    /** User-initiated retry after a failed BLE identity exchange: a
     * full link reset + fresh connect (see BleTransport.resetLink). */
    fun retryBleLinkForPeer(peerId: String) {
        val transport = _bleTransport ?: return
        val address = bleAddressForPeer(peerId) ?: return
        runCatching { transport.resetLink(address) }
    }

    /** Peers with an identity-bound, connected Nearby session right now. */
    fun nearbyBoundPeerIds(): Set<String> {
        val nearby = _nearbyTransport ?: return emptySet()
        return nearby.connectedEndpointIds().mapNotNull { peerIdByEndpoint[it] }.toSet()
    }

    fun isNearbyConnected(peerId: String): Boolean = peerId in nearbyBoundPeerIds()

    // ------------------------------------------------------------ calls

    @Volatile
    private var _lastCallPeerId: String? = null

    /** The peer of the most recent outgoing call attempt — the call
     *  screen's "Try again" button dials this. Null before any call. */
    fun lastCallPeerId(): String? = _lastCallPeerId

    /** Start an outgoing nearby call, remembering the peer so a failed
     *  call can be retried (and instantly-failed attempts can still be
     *  written to the call history with the right peer). */
    fun startVoiceCall(peerId: String) {
        _lastCallPeerId = peerId
        _callController?.startCall(peerId)
    }

    /**
     * Call history recorder: feeds every call-state transition into a
     * [CallRecordBuilder] and persists exactly one row per attempt.
     * The builder (core-common, unit-tested) owns the outcome logic;
     * this collector only translates states and stores the result.
     * A Failed state with no attempt in flight can still be a real
     * attempt — startCall fails instantly when Play services is
     * missing, before any Linking state — so it is attributed to
     * [lastCallPeerId] when known.
     */
    private fun startCallRecorder(callController: NearbyCallController) {
        val builder = CallRecordBuilder()
        appScope.launch {
            var previous: CallState = CallState.Idle
            callController.state.collect { state ->
                val now = System.currentTimeMillis()
                fun nameOf(peerId: String, stateName: String?): String? =
                    stateName ?: peerTable.get(peerId)?.displayName
                fun persist(rawReason: String?) {
                    val draft = builder.onFinished(rawReason, System.currentTimeMillis())
                    if (draft != null) {
                        appScope.launch { runCatching { callHistoryRepository.record(draft) } }
                    }
                }
                when (state) {
                    is CallState.Linking ->
                        builder.onOutgoingStarted(state.peerId, nameOf(state.peerId, state.peerName), now)
                    is CallState.Outgoing ->
                        builder.onOutgoingStarted(state.peerId, nameOf(state.peerId, state.peerName), now)
                    is CallState.Incoming ->
                        builder.onIncomingStarted(state.peerId, nameOf(state.peerId, state.peerName), now)
                    is CallState.Active ->
                        builder.onAnswered(state.connectedSinceMs)
                    is CallState.Ended -> persist(state.reason)
                    is CallState.Failed -> {
                        if (!builder.inProgress) {
                            _lastCallPeerId?.let { peerId ->
                                builder.onOutgoingStarted(peerId, nameOf(peerId, null), now)
                            }
                        }
                        persist(state.reason)
                    }
                    CallState.Idle -> {
                        if (previous is CallState.Incoming) {
                            // reject() goes straight to Idle.
                            builder.onRejectedByMe()
                            persist(null)
                        } else if (builder.inProgress) {
                            persist(null)
                        }
                    }
                }
                previous = state
            }
        }
    }

    // ------------------------------------------------------- Wi-Fi calls

    @Volatile
    private var _lastWifiCallPeerId: String? = null

    @Volatile
    private var _lastWifiCallVideo: Boolean = false

    /** The peer of the most recent Wi-Fi call attempt (for retries and
     *  for attributing instant failures in the call history). */
    fun lastWifiCallPeerId(): String? = _lastWifiCallPeerId

    /** Whether the most recent Wi-Fi call attempt was a video call. */
    fun lastWifiCallVideo(): Boolean = _lastWifiCallVideo

    /** Start an outgoing Wi-Fi Direct voice/video call. */
    fun startWifiCall(peerId: String, video: Boolean) {
        _lastWifiCallPeerId = peerId
        _lastWifiCallVideo = video
        _wifiCallController?.startCall(peerId, video)
    }

    /**
     * Seal + sign + flood a SIGNAL envelope (call signalling). Mirrors
     * ChatRepository's TEXT sealing exactly — same crypto, same peer
     * keys — only the envelope type and the router path differ: signals
     * are fire-and-forget (no outbox, no ACK), see
     * [MeshRouter.sendSignal]. Fails quietly (returns false) when the
     * peer's key is not known yet; the caller's own timeouts report
     * the failure honestly.
     */
    private suspend fun sendSealedSignal(peerId: String, text: String): Boolean {
        val identity = _identity.value ?: return false
        val router = _router ?: return false
        val peer = peerTable.get(peerId) ?: return false
        val recipientKey = peer.x25519Public ?: return false
        val now = System.currentTimeMillis()
        val sealed = crypto.seal(identity, recipientKey, text.toByteArray(Charsets.UTF_8))
        val unsigned = MessageEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = EnvelopeType.SIGNAL,
            senderId = identity.peerId,
            recipientId = peerId,
            timestampMs = now,
            expiryMs = now + MessageEnvelope.DEFAULT_EXPIRY_MS,
            ttl = MessageEnvelope.DEFAULT_TTL,
            hopCount = 0,
            ephemeralPubKey = sealed.ephemeralPubKey,
            nonce = sealed.nonce,
            ciphertext = sealed.ciphertext
        )
        val envelope = crypto.signEnvelope(identity, unsigned)
        return runCatching { router.sendSignal(peerId, envelope) }.getOrDefault(false)
    }

    /** Call-history recorder for Wi-Fi calls — same builder and rules
     *  as [startCallRecorder], fed from [WifiCallState] transitions. */
    private fun startWifiCallRecorder(controller: WifiCallController) {
        val builder = CallRecordBuilder()
        appScope.launch {
            var previous: WifiCallState = WifiCallState.Idle
            controller.state.collect { state ->
                val now = System.currentTimeMillis()
                fun nameOf(peerId: String, stateName: String?): String? =
                    stateName ?: peerTable.get(peerId)?.displayName
                fun persist(rawReason: String?) {
                    val draft = builder.onFinished(rawReason, System.currentTimeMillis())
                    if (draft != null) {
                        appScope.launch { runCatching { callHistoryRepository.record(draft) } }
                    }
                }
                when (state) {
                    is WifiCallState.Preparing ->
                        builder.onOutgoingStarted(state.peerId, nameOf(state.peerId, state.peerName), now)
                    is WifiCallState.Ringing ->
                        builder.onOutgoingStarted(state.peerId, nameOf(state.peerId, state.peerName), now)
                    is WifiCallState.Incoming ->
                        builder.onIncomingStarted(state.peerId, nameOf(state.peerId, state.peerName), now)
                    is WifiCallState.Connecting -> {
                        if (!builder.inProgress) {
                            _lastWifiCallPeerId?.let { peerId ->
                                builder.onOutgoingStarted(peerId, nameOf(peerId, null), now)
                            }
                        }
                    }
                    is WifiCallState.Active ->
                        builder.onAnswered(state.connectedSinceMs)
                    is WifiCallState.Ended -> persist(state.reason)
                    is WifiCallState.Failed -> {
                        if (!builder.inProgress) {
                            _lastWifiCallPeerId?.let { peerId ->
                                builder.onOutgoingStarted(peerId, nameOf(peerId, null), now)
                            }
                        }
                        persist(state.reason)
                    }
                    WifiCallState.Idle -> {
                        if (previous is WifiCallState.Incoming) {
                            builder.onRejectedByMe()
                            persist(null)
                        } else if (builder.inProgress) {
                            persist(null)
                        }
                    }
                }
                previous = state
            }
        }
    }

    // ------------------------------------------------------------ wiring

    private fun wireIdentity(identity: Identity) {
        _identity.value = identity
        if (_chatRepository != null) return // already wired (rename path)

        val transport = BleTransport(appContext, identity.peerId) {
            // IDENTITY characteristic payload, read live so renames apply.
            _identity.value?.let { IdentityPayloadCodec.encode(it) } ?: ByteArray(0)
        }
        // Link diagnostics (identity read retries, collisions, resets)
        // join the crash-report breadcrumb trail, so a field report
        // carries the exact event sequence that led to it.
        transport.diagnosticsListener = { message ->
            CrashReporter.breadcrumb(appContext, message)
        }
        _bleTransport = transport

        // Nearby Connections direct link (additional transport; the BLE
        // mesh below remains the multi-hop backbone). Same identity
        // payload, exchanged over the Nearby connection instead of GATT.
        val nearby = NearbyTransport(appContext, identity.peerId) {
            _identity.value?.let { IdentityPayloadCodec.encode(it) } ?: ByteArray(0)
        }
        _nearbyTransport = nearby
        // Listen from the start (v1.2.1): with permissions granted this
        // phone advertises + discovers right away, so calls and messages
        // can ARRIVE without the user first opening a chat or placing a
        // call. Before this, Nearby only started from those two places —
        // an incoming call could never ring a phone that hadn't.
        if (NearbyPermissions.hasAll(appContext)) {
            runCatching { nearby.start() }
        }

        val callController = NearbyCallController(
            context = appContext,
            transport = nearby,
            boundEndpointForPeer = { peerId -> endpointByPeerId[peerId] },
            peerForEndpoint = { endpointId -> peerIdByEndpoint[endpointId] },
            requestLink = { peerId -> startDirectLink(peerId) },
            nameForPeer = { peerId -> peerTable.get(peerId)?.displayName },
            scope = appScope
        )
        _callController = callController
        startCallRecorder(callController)

        // Wi-Fi Direct voice/video calls (v1.3.0): the invite rides the
        // encrypted mesh as a SIGNAL envelope; media stays on the local
        // Wi-Fi Direct group via WebRTC. The Nearby controller above is
        // untouched — this is an additional, independent call path.
        val rtcEngine = RtcEngine(appContext)
        _rtcEngine = rtcEngine
        val wifiCalls = WifiCallController(
            context = appContext,
            engine = rtcEngine,
            myPeerId = { _identity.value?.peerId },
            sendSignal = { peerId, text ->
                appScope.launch { sendSealedSignal(peerId, text) }
            },
            nameForPeer = { peerId -> peerTable.get(peerId)?.displayName },
            isBusyElsewhere = {
                val s = _callController?.state?.value
                s != null && s !is CallState.Idle
            },
            scope = appScope
        )
        _wifiCallController = wifiCalls
        startWifiCallRecorder(wifiCalls)

        // Sends fan out: Nearby direct link first (fast, when bound +
        // connected), then the BLE mesh (relays, always the fallback).
        val meshTransport = CompositeMeshTransport(
            listOf(
                NearbyMeshTransport(
                    nearby = nearby,
                    endpointForPeer = { peerId -> endpointByPeerId[peerId] },
                    peerForEndpoint = { endpointId -> peerIdByEndpoint[endpointId] },
                    scope = appScope
                ),
                BleMeshTransport(transport, appScope)
            )
        )
        val router = MeshRouter(
            identity = identity,
            crypto = crypto,
            transport = meshTransport,
            outbox = outboxStore,
            peerTable = peerTable
        )
        _router = router

        val chatRepo = ChatRepository(database, crypto, identity, router, voiceDir)
        _chatRepository = chatRepo

        startCollectors(transport, router, chatRepo)
        startNearbyCollectors(nearby, router, callController)
    }

    private fun startCollectors(
        transport: BleTransport,
        router: MeshRouter,
        chatRepo: ChatRepository
    ) {
        // -- Identity payloads read from peers -> mapping + tables + Room.
        appScope.launch {
            transport.peerIdentities.collect { byAddress ->
                byAddress.forEach { (address, payload) ->
                    handleIdentityPayload(transport, address, payload)
                }
            }
        }

        // -- Raw inbound envelope bytes -> router.
        appScope.launch {
            transport.incoming.collect { incoming ->
                val envelope = runCatching {
                    EnvelopeCodec.deserialize(incoming.data)
                }.getOrNull()
                // The router needs the *carrier's* peerId (split horizon,
                // per-peer rate limiting). Resolve from the identity
                // mapping first; fall back to the envelope's originator —
                // correct for the common 1-hop case.
                // TODO(contract): BleTransport surfaces only the BLE
                // address; if the carrier's IDENTITY read has not completed
                // yet and the envelope is a relayed one, carrier and
                // originator differ and split-horizon is approximate until
                // the mapping lands.
                val fromPeerId = peerIdByAddress[incoming.address]
                    ?: transport.peers.value.firstOrNull { it.deviceAddress == incoming.address }?.peerId
                    ?: envelope?.senderId
                if (fromPeerId != null) {
                    router.onIncomingBytes(fromPeerId, incoming.data)
                }
                // else: carrier unresolved and envelope undecodable — bytes
                // cannot be routed or attributed; dropped (no fake routing).
            }
        }

        // -- Router events -> Room (single source of truth for the UI).
        appScope.launch {
            router.events.collect { event ->
                when (event) {
                    is MeshEvent.MessageReceived -> {
                        val envelope = event.envelope
                        if (envelope.type == EnvelopeType.TEXT) {
                            // The production router decrypts before
                            // emitting; plaintext == null for a TEXT event
                            // means decryption failed and the router did
                            // NOT ACK — nothing is persisted, so the UI
                            // never shows a message we could not read.
                            val plaintext = event.plaintext
                            if (plaintext != null) {
                                chatRepo.handleIncoming(
                                    envelope,
                                    String(plaintext, Charsets.UTF_8)
                                )
                            }
                        } else if (envelope.type == EnvelopeType.BLOB) {
                            // One voice-note chunk. The repository tracks
                            // it and only surfaces the note once ALL
                            // chunks arrived and the hash verifies.
                            val chunk = event.plaintext
                            if (chunk != null) {
                                chatRepo.handleIncomingBlobChunk(envelope, chunk)
                            }
                        } else if (envelope.type == EnvelopeType.SIGNAL) {
                            // Call signalling (v1.3.0): decrypted by the
                            // router like a message, but routed to the
                            // Wi-Fi call controller instead of the chat.
                            val plain = event.plaintext
                            if (plain != null) {
                                val text = String(plain, Charsets.UTF_8)
                                if (text.startsWith(WifiCallController.PREFIX)) {
                                    _wifiCallController?.onMeshSignal(envelope.senderId, text)
                                }
                            }
                        }
                        // PING / KEY_EXCHANGE / ACK envelopes are handled
                        // inside the router; nothing to persist here.
                    }
                    is MeshEvent.AckReceived -> {
                        // Only the recipient's own ACK may mark DELIVERED.
                        chatRepo.updateDeliveryState(
                            event.originalMessageId,
                            DeliveryState.DELIVERED
                        )
                        chatRepo.onChunkDeliveryEvent(
                            event.originalMessageId,
                            DeliveryState.DELIVERED
                        )
                    }
                    is MeshEvent.DeliveryStateChanged -> {
                        chatRepo.updateDeliveryState(event.messageId, event.state)
                        chatRepo.onChunkDeliveryEvent(event.messageId, event.state)
                    }
                    is MeshEvent.Expired -> {
                        chatRepo.updateDeliveryState(event.messageId, DeliveryState.EXPIRED)
                        chatRepo.onChunkDeliveryEvent(event.messageId, DeliveryState.EXPIRED)
                    }
                    is MeshEvent.Forwarded -> {
                        // A neighbour accepting our envelope is RELAYED,
                        // already reflected via DeliveryStateChanged. A
                        // relay hop of someone else's message changes no
                        // local conversation state.
                        Unit
                    }
                }
            }
        }

        // -- A new READY link may unblock the store-and-forward queue.
        appScope.launch {
            var previousReady: Set<String> = emptySet()
            transport.connections.collect { connections ->
                val ready = connections.filterValues { it == ConnState.READY }.keys
                if (ready.size > previousReady.size) {
                    runCatching { router.flushOutbox() }
                }
                previousReady = ready
            }
        }

        // -- Advertisement identity hints -> resolve known peers pre-connect.
        appScope.launch {
            transport.peers.collect { peers ->
                peers.forEach { peer ->
                    val hint = peer.identityHint ?: return@forEach
                    if (peer.peerId != null) return@forEach
                    val match = peerTable.all().firstOrNull { known ->
                        BleTransport.identityHint(known.peerId).contentEquals(hint)
                    } ?: return@forEach
                    transport.setPeerIdentity(peer.deviceAddress, match.peerId, match.displayName)
                    peerIdByAddress[peer.deviceAddress] = match.peerId
                    addressByPeerId[match.peerId] = peer.deviceAddress
                    peerTable.markSeen(match.peerId, System.currentTimeMillis(), peer.deviceAddress)
                }
            }
        }
    }

    private fun handleIdentityPayload(transport: BleTransport, address: String, payload: ByteArray) {
        val decoded = IdentityPayloadCodec.decode(payload) ?: return
        peerIdByAddress[address] = decoded.peerId
        addressByPeerId[decoded.peerId] = address
        transport.setPeerIdentity(address, decoded.peerId, decoded.displayName)
        val info = PeerInfo(
            peerId = decoded.peerId,
            address = address,
            x25519Public = decoded.x25519Public,
            ed25519Public = decoded.ed25519Public,
            displayName = decoded.displayName,
            lastSeenMs = System.currentTimeMillis(),
            hopsAway = 1
        )
        peerTable.upsert(info)
        appScope.launch { peerRepository.recordPeerInfo(info) }
    }

    // ---------------------------------------------------- Nearby wiring

    private fun startNearbyCollectors(
        nearby: NearbyTransport,
        router: MeshRouter,
        callController: NearbyCallController
    ) {
        // -- Inbound BYTES payloads: demux mesh envelopes / identity / calls.
        appScope.launch {
            nearby.incomingBytes.collect { incoming ->
                val data = incoming.bytes
                val envelope = runCatching { EnvelopeCodec.deserialize(data) }.getOrNull()
                when {
                    envelope != null -> {
                        // Mesh envelope over the direct link. Carrier =
                        // the bound peer for this endpoint; fall back to
                        // the envelope originator (1-hop direct link).
                        val fromPeerId = peerIdByEndpoint[incoming.endpointId]
                            ?: envelope.senderId
                        router.onIncomingBytes(fromPeerId, data)
                    }
                    data.isNotEmpty() && data[0] == '{'.code.toByte() -> {
                        handleNearbyIdentity(incoming.endpointId, data)
                    }
                    else -> {
                        val text = runCatching { String(data, Charsets.UTF_8) }.getOrNull()
                        if (text != null && text.startsWith(NearbyCallController.PREFIX)) {
                            callController.onControlBytes(incoming.endpointId, text)
                        }
                        // Anything else is unrecognised and dropped.
                    }
                }
            }
        }

        // -- Inbound STREAM payloads belong to the call controller.
        appScope.launch {
            nearby.incomingStreams.collect { stream ->
                callController.onAudioStream(stream.endpointId, stream.input)
            }
        }

        // -- A freshly connected direct link may unblock the outbox too.
        appScope.launch {
            var previousConnected: Set<String> = emptySet()
            nearby.sessions.collect { sessions ->
                val connected = sessions.filterValues {
                    it == com.p2pchat.messenger.nearby.NearbyLinkState.CONNECTED
                }.keys
                if (connected.size > previousConnected.size) {
                    runCatching { router.flushOutbox() }
                }
                previousConnected = connected
            }
        }
    }

    /** Identity payload received over a Nearby connection: bind the
     *  endpoint to the verified peerId (the payload carries the same
     *  keys as the BLE exchange; the mesh layer's signature checks on
     *  envelopes remain the actual trust anchor). */
    private fun handleNearbyIdentity(endpointId: String, payload: ByteArray) {
        val decoded = IdentityPayloadCodec.decode(payload) ?: return
        peerIdByEndpoint[endpointId] = decoded.peerId
        endpointByPeerId[decoded.peerId] = endpointId
        val info = PeerInfo(
            peerId = decoded.peerId,
            address = NEARBY_ADDRESS_PREFIX + endpointId,
            x25519Public = decoded.x25519Public,
            ed25519Public = decoded.ed25519Public,
            displayName = decoded.displayName,
            lastSeenMs = System.currentTimeMillis(),
            hopsAway = 1
        )
        peerTable.upsert(info)
        appScope.launch { peerRepository.recordPeerInfo(info) }
    }

    companion object {
        /** PeerInfo.address prefix for peers learned over Nearby (the rest
         *  is the Nearby endpointId, NOT a BLE address). */
        const val NEARBY_ADDRESS_PREFIX = "nearby:"
    }
}

/**
 * [MeshTransport] bridge over [NearbyTransport]: a peer is "connected"
 * here only when its Nearby endpoint is identity-bound AND the session
 * is CONNECTED. Payloads above the Nearby BYTES limit fail here so the
 * composite falls back to the BLE mesh path.
 */
private class NearbyMeshTransport(
    private val nearby: NearbyTransport,
    private val endpointForPeer: (String) -> String?,
    private val peerForEndpoint: (String) -> String?,
    private val scope: CoroutineScope
) : MeshTransport {

    override fun connectedPeerIds(): Set<String> =
        nearby.connectedEndpointIds().mapNotNull(peerForEndpoint).toSet()

    override suspend fun sendTo(peerId: String, bytes: ByteArray): Boolean {
        val endpointId = endpointForPeer(peerId) ?: return false
        if (endpointId !in nearby.connectedEndpointIds()) return false
        return nearby.sendBytes(endpointId, bytes)
    }

    override fun broadcastExcept(excludePeerId: String?, bytes: ByteArray) {
        scope.launch {
            connectedPeerIds()
                .filter { it != excludePeerId }
                .forEach { peerId -> runCatching { sendTo(peerId, bytes) } }
        }
    }
}

/**
 * [MeshTransport] bridge over [BleTransport]: translates mesh peerIds to
 * BLE addresses using the transport's READY links + resolved identities.
 */
private class BleMeshTransport(
    private val transport: BleTransport,
    private val scope: CoroutineScope
) : MeshTransport {

    override fun connectedPeerIds(): Set<String> =
        transport.readyPeerIds().values.toSet()

    override suspend fun sendTo(peerId: String, bytes: ByteArray): Boolean {
        val address = transport.readyPeerIds().entries
            .firstOrNull { it.value == peerId }?.key ?: return false
        return transport.send(address, bytes)
    }

    override fun broadcastExcept(excludePeerId: String?, bytes: ByteArray) {
        scope.launch {
            connectedPeerIds()
                .filter { it != excludePeerId }
                .forEach { peerId -> runCatching { sendTo(peerId, bytes) } }
        }
    }
}

/**
 * App-level identity payload served over the BLE IDENTITY characteristic:
 * a small JSON object `{v, peerId, name, ed, x}` with the two public keys
 * Base64-encoded. The BLE layer treats the payload as opaque bytes.
 *
 * TODO(contract): the payload is ~230 bytes, larger than one ATT read at
 * the default 23-byte MTU. BleTransport requests MTU 517 before service
 * discovery, so in practice the read fits; on a link stuck at the minimum
 * MTU the single read truncates and decode here fails cleanly (peer stays
 * "identity unknown") — long-read continuation is a transport-layer gap.
 */
internal object IdentityPayloadCodec {

    class Decoded(
        val peerId: String,
        val displayName: String?,
        val ed25519Public: ByteArray,
        val x25519Public: ByteArray
    )

    fun encode(identity: Identity): ByteArray {
        val json = JSONObject().apply {
            put("v", 1)
            put("peerId", identity.peerId)
            put("name", identity.displayName)
            put("ed", Base64.encodeToString(identity.ed25519Public, Base64.NO_WRAP))
            put("x", Base64.encodeToString(identity.x25519Public, Base64.NO_WRAP))
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): Decoded? = runCatching {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val peerId = json.getString("peerId")
        require(PeerIds.isValid(peerId)) { "invalid peerId in identity payload" }
        val ed = Base64.decode(json.getString("ed"), Base64.NO_WRAP)
        val x = Base64.decode(json.getString("x"), Base64.NO_WRAP)
        require(ed.size == 32 && x.size == 32) { "public keys must be 32 bytes" }
        Decoded(
            peerId = peerId,
            displayName = json.optString("name").takeIf { it.isNotBlank() },
            ed25519Public = ed,
            x25519Public = x
        )
    }.getOrNull()
}
