package com.p2pchat.messenger

import android.app.Application
import android.content.Context
import android.util.Base64
import com.p2pchat.messenger.ble.BleTransport
import com.p2pchat.messenger.ble.model.ConnState
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
import com.p2pchat.messenger.data.repository.ChatRepository
import com.p2pchat.messenger.data.repository.PeerRepository
import com.p2pchat.messenger.mesh.DeliveryState
import com.p2pchat.messenger.mesh.MeshEvent
import com.p2pchat.messenger.mesh.MeshRouter
import com.p2pchat.messenger.mesh.MeshTransport
import com.p2pchat.messenger.mesh.OutboxBackoff
import com.p2pchat.messenger.mesh.OutboxStore
import com.p2pchat.messenger.mesh.PeerInfo
import com.p2pchat.messenger.mesh.PeerTable
import com.p2pchat.messenger.mesh.QueuedMessage
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

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Diagnostic build: capture any startup crash to a file BEFORE any
        // app machinery runs, so MainActivity can show it on next launch.
        CrashReporter.install(this)
        try {
            container = AppContainer(this)
            CrashReporter.breadcrumb(this, "container-created")
            container.bootstrap()
            CrashReporter.breadcrumb(this, "bootstrap-done")
        } catch (t: Throwable) {
            CrashReporter.recordThrowable(this, "P2PApp.onCreate", t)
            throw t
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
    private var _chatRepository: ChatRepository? = null

    /** Non-null only after an identity exists. */
    val chatRepository: ChatRepository? get() = _chatRepository

    // BLE address <-> mesh peerId mapping, learned from IDENTITY reads.
    private val peerIdByAddress = ConcurrentHashMap<String, String>()
    private val addressByPeerId = ConcurrentHashMap<String, String>()

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
        // TODO(contract): PeerDao has no clearAll(); delete row by row.
        peerRepository.peers.first().forEach { database.peerDao().deleteById(it.peerId) }
        peerTable.clear()
        peerIdByAddress.clear()
        addressByPeerId.clear()
        identityStore.clear()
        _identity.value = null
    }

    // ------------------------------------------------------------ wiring

    private fun wireIdentity(identity: Identity) {
        _identity.value = identity
        if (_chatRepository != null) return // already wired (rename path)

        val transport = BleTransport(appContext, identity.peerId) {
            // IDENTITY characteristic payload, read live so renames apply.
            _identity.value?.let { IdentityPayloadCodec.encode(it) } ?: ByteArray(0)
        }
        _bleTransport = transport

        val meshTransport = BleMeshTransport(transport, appScope)
        val router = MeshRouter(
            identity = identity,
            crypto = crypto,
            transport = meshTransport,
            outbox = outboxStore,
            peerTable = peerTable
        )
        _router = router

        // NOTE(contract): ChatRepository.dispatch() calls `router.send(...)`,
        // a method MeshRouter does not expose (it has sendText/sendEnvelope).
        // That call lives inside core-database, so wiring here is correct
        // regardless; see the delivery report for the one-line fix needed
        // in ChatRepository.
        val chatRepo = ChatRepository(database, crypto, identity, router)
        _chatRepository = chatRepo

        startCollectors(transport, router, chatRepo)
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
                    }
                    is MeshEvent.DeliveryStateChanged -> {
                        chatRepo.updateDeliveryState(event.messageId, event.state)
                    }
                    is MeshEvent.Expired -> {
                        chatRepo.updateDeliveryState(event.messageId, DeliveryState.EXPIRED)
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
