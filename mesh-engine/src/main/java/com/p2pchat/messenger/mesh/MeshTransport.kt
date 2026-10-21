package com.p2pchat.messenger.mesh

/**
 * The transport seam between the mesh router and any byte pipe underneath
 * (in production: `:transport-ble`'s BleTransport bridged address↔peerId;
 * in tests: an in-memory fake network).
 *
 * The router depends ONLY on this interface, so the whole mesh protocol is
 * unit-testable on the JVM without Android or Bluetooth hardware.
 */
interface MeshTransport {

    /** Peer IDs reachable *right now* in one hop. Empty when isolated. */
    fun connectedPeerIds(): Set<String>

    /**
     * Send raw (already serialized) envelope bytes to one direct neighbour.
     * @return true if the neighbour's link accepted the bytes. Acceptance by
     * a relay is a hop, never proof of final delivery.
     */
    suspend fun sendTo(peerId: String, bytes: ByteArray): Boolean

    /**
     * Fire-and-forget flood to every connected peer except [excludePeerId]
     * (split horizon). Used for announcements / ACK fan-out where the caller
     * does not track per-peer success.
     */
    fun broadcastExcept(excludePeerId: String?, bytes: ByteArray)
}

/** Events emitted by [MeshRouter] on its `events` flow. */
sealed class MeshEvent {

    /**
     * An envelope addressed to this node arrived. [plaintext] is the
     * decrypted body when the router was given a decryptor and decryption
     * succeeded; it is null for ACK/PING/KEY_EXCHANGE envelopes, when no
     * decryptor is configured, or when decryption failed — the raw
     * [envelope] is always present so the app layer can retry/decrypt.
     */
    data class MessageReceived(
        val envelope: com.p2pchat.messenger.core.model.MessageEnvelope,
        val plaintext: ByteArray?,
    ) : MeshEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MessageReceived) return false
            return envelope == other.envelope && plaintext.contentEquals(other.plaintext)
        }

        override fun hashCode(): Int = 31 * envelope.hashCode() + plaintext.contentHashCode()
    }

    /** The recipient device ACKed [originalMessageId]. This — and only this —
     * moves a message to [DeliveryState.DELIVERED]. */
    data class AckReceived(val originalMessageId: String, val fromPeerId: String) : MeshEvent()

    /** This node forwarded [messageId] onward to neighbour [toPeerId]. */
    data class Forwarded(val messageId: String, val toPeerId: String) : MeshEvent()

    /** [messageId] expired in the outbox / on arrival and was dropped. */
    data class Expired(val messageId: String) : MeshEvent()

    /** Outgoing [messageId] changed [DeliveryState] (QUEUED → RELAYED → DELIVERED…). */
    data class DeliveryStateChanged(val messageId: String, val state: DeliveryState) : MeshEvent()
}
