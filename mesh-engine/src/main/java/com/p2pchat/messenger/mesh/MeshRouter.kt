package com.p2pchat.messenger.mesh

import com.p2pchat.messenger.core.codec.EnvelopeCodec
import com.p2pchat.messenger.core.mesh.SeenMessageCache
import com.p2pchat.messenger.core.model.EnvelopeType
import com.p2pchat.messenger.core.model.MessageEnvelope
import com.p2pchat.messenger.crypto.CryptoManager
import com.p2pchat.messenger.crypto.Identity
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Application-level multi-hop flood router — the mesh protocol itself.
 * Bluetooth does NOT route beyond one hop; everything beyond a direct
 * neighbour happens here, explicitly:
 *
 * ```
 * A ── B ── C ── D     A sends to D: B and C re-flood the sealed envelope,
 *                      D decrypts and floods an ACK back; only D's ACK
 *                      moves A's copy to DELIVERED.
 * ```
 *
 * Protocol rules implemented in [onIncomingBytes]:
 *  1. **Dedup** — [SeenMessageCache] drops any message ID already handled
 *     (loop prevention + duplicate suppression in one).
 *  2. **Expiry** — envelopes past `expiryMs` are dropped, never forwarded.
 *  3. **TTL / hop cap** — the shared envelope model decrements `ttl` on
 *     every hop ([MessageEnvelope.incrementedHop]); forwarding stops when
 *     `ttl == 0` or `hopCount >= MAX_HOPS`, however the envelope arrived.
 *  4. **Split horizon** — a relay never forwards back to the peer it got
 *     the envelope from.
 *  5. **Rate limit** — at most 30 forwards/min triggered per source peer
 *     ([RateLimiter]); excess is dropped, not queued.
 *  6. **Relays are blind** — forwarding works on the sealed envelope only;
 *     relays never see plaintext and cannot decrypt what they carry.
 *  7. **No false delivery** — a neighbour accepting bytes moves a message
 *     to RELAYED only. DELIVERED requires the recipient's own ACK.
 *
 * Delivery-to-me vs forwarding order matters: an envelope whose remaining
 * TTL reached 0 *at its destination* is still delivered — TTL gates
 * forwarding, not final delivery.
 *
 * @param decryptor decrypts a TEXT envelope addressed to us; returning null
 *   (or throwing) means "not really received" — no ACK is sent and the
 *   sender keeps the message queued. When null, the router runs in
 *   pass-through mode: it emits the envelope and ACKs on receipt, and the
 *   app layer owns decryption.
 * @param signer signs envelopes this node creates (ACKs). When null, ACKs
 *   are sent unsigned (test / pass-through mode only).
 * @param verifier when non-null, inbound envelopes failing verification are
 *   dropped before any processing.
 */
class MeshRouter(
    val localPeerId: String,
    private val transport: MeshTransport,
    private val outbox: OutboxStore,
    val peerTable: PeerTable = PeerTable(),
    private val seenCache: SeenMessageCache = SeenMessageCache(),
    private val decryptor: ((MessageEnvelope) -> ByteArray?)? = null,
    private val signer: ((MessageEnvelope) -> MessageEnvelope)? = null,
    private val verifier: ((MessageEnvelope) -> Boolean)? = null,
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * Production constructor: binds the router to the node's real identity.
     * Inbound envelopes are signature-verified against the peer table's
     * Ed25519 keys (unknown senders fail verification and are dropped),
     * TEXT payloads are opened with [CryptoManager], and ACKs are signed.
     */
    constructor(
        identity: Identity,
        crypto: CryptoManager,
        transport: MeshTransport,
        outbox: OutboxStore,
        peerTable: PeerTable = PeerTable(),
    ) : this(
        localPeerId = identity.peerId,
        transport = transport,
        outbox = outbox,
        peerTable = peerTable,
        decryptor = { env ->
            crypto.open(
                identity,
                env.ephemeralPubKey,
                CryptoManager.SealedPayload(env.ephemeralPubKey, env.nonce, env.ciphertext),
            )
        },
        signer = { env -> crypto.signEnvelope(identity, env) },
        verifier = { env ->
            crypto.verifyEnvelope({ senderId -> peerTable.get(senderId)?.ed25519Public }, env)
        },
    )

    private val _events = MutableSharedFlow<MeshEvent>(
        replay = 64,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    private val _deliveryStates = MutableStateFlow<Map<String, DeliveryState>>(emptyMap())
    val deliveryStates: StateFlow<Map<String, DeliveryState>> = _deliveryStates.asStateFlow()

    fun deliveryStateOf(messageId: String): DeliveryState? = _deliveryStates.value[messageId]

    private fun setState(messageId: String, state: DeliveryState) {
        _deliveryStates.value = _deliveryStates.value.toMutableMap().apply { put(messageId, state) }
        _events.tryEmit(MeshEvent.DeliveryStateChanged(messageId, state))
    }

    // ------------------------------------------------------------ sending

    /**
     * Queue [sealedEnvelope] (already sealed + signed by the caller) in the
     * outbox and flood it to every currently connected neighbour.
     * @return true if at least one neighbour accepted it (→ RELAYED).
     * The message stays in the outbox until the recipient's ACK returns or
     * it expires — relay acceptance never removes it.
     */
    suspend fun sendText(recipientId: String, sealedEnvelope: MessageEnvelope): Boolean {
        require(sealedEnvelope.recipientId == recipientId) {
            "Envelope recipient ${sealedEnvelope.recipientId} != sendText recipient $recipientId"
        }
        require(sealedEnvelope.senderId == localPeerId) {
            "Envelope sender ${sealedEnvelope.senderId} != local peer $localPeerId"
        }
        // Our own message coming back around a loop must be recognised.
        seenCache.markSeen(sealedEnvelope.messageId)
        if (sealedEnvelope.isExpired(clock())) {
            setState(sealedEnvelope.messageId, DeliveryState.EXPIRED)
            _events.tryEmit(MeshEvent.Expired(sealedEnvelope.messageId))
            return false
        }
        outbox.insert(sealedEnvelope)
        setState(sealedEnvelope.messageId, DeliveryState.QUEUED)
        val bytes = EnvelopeCodec.serialize(sealedEnvelope)
        val acceptedBy = flood(bytes, excludePeerId = null, originalMessageId = sealedEnvelope.messageId)
        if (acceptedBy.isNotEmpty() && deliveryStateOf(sealedEnvelope.messageId) != DeliveryState.DELIVERED) {
            setState(sealedEnvelope.messageId, DeliveryState.RELAYED)
        }
        return acceptedBy.isNotEmpty()
    }

    /** Alias for [sendText] for non-TEXT envelopes (PING / KEY_EXCHANGE). */
    suspend fun sendEnvelope(envelope: MessageEnvelope): Boolean =
        sendText(envelope.recipientId, envelope)

    /**
     * Retry pass over the outbox: purge expired envelopes (emitting
     * [MeshEvent.Expired]), then re-flood every due envelope to the
     * neighbours connected right now. Envelopes nobody accepts get their
     * attempt counter incremented (exponential backoff in the store).
     * Call this on reconnect, on a timer, and when the peer set changes.
     * @return how many envelopes at least one neighbour accepted this pass.
     */
    suspend fun flushOutbox(): Int {
        val now = clock()
        outbox.queued()
            .filter { it.envelope.expiryMs <= now }
            .forEach { queued ->
                setState(queued.messageId, DeliveryState.EXPIRED)
                _events.tryEmit(MeshEvent.Expired(queued.messageId))
            }
        outbox.purgeExpired(now)

        var relayedCount = 0
        for (envelope in outbox.pending(now)) {
            val bytes = EnvelopeCodec.serialize(envelope)
            val acceptedBy = flood(bytes, excludePeerId = null, originalMessageId = envelope.messageId)
            if (acceptedBy.isNotEmpty()) {
                relayedCount++
                if (deliveryStateOf(envelope.messageId) != DeliveryState.DELIVERED) {
                    setState(envelope.messageId, DeliveryState.RELAYED)
                }
            } else {
                outbox.incrementAttempts(envelope.messageId)
            }
        }
        return relayedCount
    }

    // ------------------------------------------------------------ receiving

    /**
     * Entry point for raw envelope bytes arriving from a direct neighbour.
     * Safe to call with garbage: malformed, duplicate, expired, forged or
     * looping input is dropped silently — radio input is never trusted.
     */
    suspend fun onIncomingBytes(fromPeerId: String, bytes: ByteArray) {
        val envelope = try {
            EnvelopeCodec.deserialize(bytes)
        } catch (e: Exception) {
            return
        }
        val now = clock()

        // Sightings: the carrier is a direct neighbour; the originator is
        // (hopCount + 1) hops away — the peer table keeps the minimum.
        peerTable.markSeen(fromPeerId, now)
        if (envelope.senderId != fromPeerId && envelope.senderId != localPeerId) {
            peerTable.upsert(
                PeerInfo(
                    peerId = envelope.senderId,
                    lastSeenMs = now,
                    hopsAway = envelope.hopCount + 1,
                ),
            )
        }

        // (1) Dedup / loop prevention. NOTE: an ID is only *marked* seen once
        // this node has actually handled the envelope (delivered it,
        // forwarded it, or terminally dropped it). An envelope we could not
        // forward because no route exists right now is queued instead (see
        // relay branch below) and deliberately left unmarked, so a later
        // retry of the same envelope after reconnection is not mistaken
        // for a duplicate — that is what makes store-and-forward work.
        if (seenCache.hasSeen(envelope.messageId)) return

        // Our own envelope completing a loop — never process or re-forward.
        if (envelope.senderId == localPeerId && envelope.type != EnvelopeType.ACK) return

        // (2) Expiry — a terminal drop, so the ID is marked.
        if (envelope.isExpired(now)) {
            seenCache.markSeen(envelope.messageId)
            _events.tryEmit(MeshEvent.Expired(envelope.messageId))
            return
        }

        // Authentication, when configured — also terminal.
        if (verifier != null) {
            val ok = try {
                verifier.invoke(envelope)
            } catch (e: Exception) {
                false
            }
            if (!ok) {
                seenCache.markSeen(envelope.messageId)
                return
            }
        }

        val forMe = envelope.recipientId == localPeerId
        val broadcast = envelope.recipientId == MessageEnvelope.BROADCAST

        when {
            envelope.type == EnvelopeType.ACK && forMe -> {
                seenCache.markSeen(envelope.messageId)
                handleAck(envelope)
            }
            envelope.type == EnvelopeType.ACK -> {
                when (tryForward(envelope, fromPeerId)) {
                    ForwardOutcome.SENT -> {
                        seenCache.markSeen(envelope.messageId)
                        // The ACK passing through proves delivery of the
                        // original: drop any relay-queued copy of it here.
                        val originalId = envelope.ciphertext.toString(Charsets.UTF_8)
                        if (originalId.isNotEmpty()) outbox.remove(originalId)
                    }
                    ForwardOutcome.NO_ROUTE -> queueRelay(envelope)
                    ForwardOutcome.DROPPED_TERMINAL -> seenCache.markSeen(envelope.messageId)
                }
            }
            broadcast -> {
                seenCache.markSeen(envelope.messageId)
                deliverToMe(envelope)
                // Broadcasts are also relayed onward (dedup stops loops).
                tryForward(envelope, fromPeerId)
            }
            forMe -> {
                val delivered = deliverToMe(envelope)
                if (delivered) {
                    seenCache.markSeen(envelope.messageId)
                    if (envelope.type == EnvelopeType.TEXT) sendAck(envelope)
                }
                // Not delivered (e.g. undecryptable until a key exchange
                // completes): deliberately left unmarked and un-ACKed, so
                // the sender's outbox retry can succeed later.
            }
            else -> {
                // Pure relay: forward if a route exists, otherwise
                // store-and-forward queue the (hop-incremented) envelope
                // for flushOutbox() when a peer appears.
                when (tryForward(envelope, fromPeerId)) {
                    ForwardOutcome.SENT -> seenCache.markSeen(envelope.messageId)
                    ForwardOutcome.NO_ROUTE -> queueRelay(envelope)
                    ForwardOutcome.DROPPED_TERMINAL -> seenCache.markSeen(envelope.messageId)
                }
            }
        }
    }

    /** Queue an envelope this node is relaying but cannot forward yet. It is
     * stored hop-incremented so [flushOutbox] can flood it as-is later. */
    private suspend fun queueRelay(envelope: MessageEnvelope) {
        if (!envelope.hasTtlRemaining() || envelope.hopCount >= MAX_HOPS) return
        outbox.insert(envelope.incrementedHop())
    }

    /** @return true if the envelope counts as received by this node. */
    private fun deliverToMe(envelope: MessageEnvelope): Boolean {
        if (envelope.type == EnvelopeType.TEXT && decryptor != null) {
            val plaintext = try {
                decryptor.invoke(envelope)
            } catch (e: Exception) {
                null
            } ?: return false // undecryptable: not received, no ACK, sender retries until expiry
            _events.tryEmit(MeshEvent.MessageReceived(envelope, plaintext))
            return true
        }
        // Pass-through mode (no decryptor) or a non-TEXT envelope: emit the
        // raw envelope; the app layer owns any further decryption.
        _events.tryEmit(MeshEvent.MessageReceived(envelope, null))
        return true
    }

    private suspend fun handleAck(envelope: MessageEnvelope) {
        // ACK convention: ciphertext carries the original message ID as
        // UTF-8. ACKs are signed, not secret, so relays may read this field;
        // it reveals only that some message was delivered, never its content.
        val originalId = envelope.ciphertext.toString(Charsets.UTF_8)
        if (originalId.isEmpty()) return
        outbox.remove(originalId)
        setState(originalId, DeliveryState.DELIVERED)
        _events.tryEmit(MeshEvent.AckReceived(originalId, envelope.senderId))
    }

    private suspend fun sendAck(original: MessageEnvelope) {
        val now = clock()
        val unsigned = MessageEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = EnvelopeType.ACK,
            senderId = localPeerId,
            recipientId = original.senderId,
            timestampMs = now,
            expiryMs = now + MessageEnvelope.DEFAULT_EXPIRY_MS,
            ttl = MessageEnvelope.DEFAULT_TTL,
            hopCount = 0,
            ciphertext = original.messageId.toByteArray(Charsets.UTF_8),
        )
        val ack = try {
            signer?.invoke(unsigned) ?: unsigned
        } catch (e: Exception) {
            unsigned
        }
        seenCache.markSeen(ack.messageId)
        // ACKs flood to ALL neighbours including the one the message came
        // from — the return path usually starts back through that relay.
        flood(EnvelopeCodec.serialize(ack), excludePeerId = null, originalMessageId = ack.messageId)
    }

    // ------------------------------------------------------------ forwarding

    private enum class ForwardOutcome {
        /** At least one neighbour accepted the envelope. */
        SENT,

        /** No usable route right now — eligible for store-and-forward. */
        NO_ROUTE,

        /** TTL / hop cap / rate limit — retrying this copy is pointless. */
        DROPPED_TERMINAL,
    }

    /**
     * Relay [envelope] one hop onward: hop count + 1 / TTL − 1 (inside
     * [MessageEnvelope.incrementedHop]), to every connected neighbour
     * except the source (split horizon).
     */
    private suspend fun tryForward(envelope: MessageEnvelope, fromPeerId: String): ForwardOutcome {
        // (3) TTL / hop cap gates forwarding — never final delivery.
        if (!envelope.hasTtlRemaining()) return ForwardOutcome.DROPPED_TERMINAL
        if (envelope.hopCount >= MAX_HOPS) return ForwardOutcome.DROPPED_TERMINAL
        val targets = transport.connectedPeerIds() - fromPeerId - localPeerId
        if (targets.isEmpty()) return ForwardOutcome.NO_ROUTE
        // (5) Per-source rate limit on triggered forwards.
        if (!rateLimiter.tryForward(fromPeerId)) return ForwardOutcome.DROPPED_TERMINAL
        val forwarded = envelope.incrementedHop()
        val bytes = EnvelopeCodec.serialize(forwarded)
        val accepted = flood(bytes, excludePeerId = fromPeerId, originalMessageId = envelope.messageId)
        return if (accepted.isNotEmpty()) ForwardOutcome.SENT else ForwardOutcome.NO_ROUTE
    }

    /** Send [bytes] to every connected neighbour except [excludePeerId].
     * @return peer IDs that accepted the bytes. */
    private suspend fun flood(
        bytes: ByteArray,
        excludePeerId: String?,
        originalMessageId: String,
    ): List<String> {
        val accepted = mutableListOf<String>()
        for (peerId in transport.connectedPeerIds()) {
            if (peerId == excludePeerId) continue
            if (peerId == localPeerId) continue
            val ok = try {
                transport.sendTo(peerId, bytes)
            } catch (e: Exception) {
                false
            }
            if (ok) {
                accepted.add(peerId)
                _events.tryEmit(MeshEvent.Forwarded(originalMessageId, peerId))
            }
        }
        return accepted
    }

    companion object {
        /** Hard hop ceiling regardless of TTL — the last line of defence
         * against malicious or buggy forwarding loops. */
        const val MAX_HOPS: Int = 12
    }
}
