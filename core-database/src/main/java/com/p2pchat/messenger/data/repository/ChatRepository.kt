package com.p2pchat.messenger.data.repository

import com.p2pchat.messenger.core.model.EnvelopeType
import com.p2pchat.messenger.core.model.MessageEnvelope
import com.p2pchat.messenger.crypto.CryptoManager
import com.p2pchat.messenger.crypto.Identity
import com.p2pchat.messenger.data.P2PDatabase
import com.p2pchat.messenger.data.entity.ConversationEntity
import com.p2pchat.messenger.data.entity.MessageEntity
import com.p2pchat.messenger.mesh.DeliveryState
import com.p2pchat.messenger.mesh.MeshRouter
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Conversations and messages for the local user.
 *
 * Sending: plaintext is sealed with [CryptoManager.seal] to the recipient's
 * X25519 public key, wrapped in a signed [MessageEnvelope], stored locally
 * as QUEUED (with its plaintext — this is the sender's own conversation),
 * and handed to the [MeshRouter]. The router / outbox own forwarding,
 * retries and ACK handling; this repository only reflects the truthful
 * [DeliveryState] the router reports back via [updateDeliveryState] and
 * [handleIncoming]. A message is marked DELIVERED only on a recipient ACK
 * (MeshEvent.AckReceived), never when a relay merely accepts it.
 *
 * TODO(contract): MeshRouter's send entry point is taken from the shared
 * contract as `router.send(envelope)`. If mesh-engine names it differently,
 * adjust only [dispatch].
 */
class ChatRepository(
    private val db: P2PDatabase,
    private val crypto: CryptoManager,
    private val identity: Identity,
    private val router: MeshRouter? = null
) {
    val conversations: Flow<List<ConversationEntity>> = db.conversationDao().observeAll()

    fun searchConversations(query: String): Flow<List<ConversationEntity>> =
        if (query.isBlank()) conversations else db.conversationDao().search(query.trim())

    fun messages(peerId: String): Flow<List<MessageEntity>> =
        db.messageDao().observeByConversation(peerId)

    fun observeMessage(messageId: String): Flow<MessageEntity?> =
        db.messageDao().observeById(messageId)

    suspend fun getMessage(messageId: String): MessageEntity? = db.messageDao().getById(messageId)

    /**
     * Seals, signs, persists (QUEUED) and dispatches a text message.
     * @throws IllegalStateException if the recipient peer (and its X25519
     * key) is not known yet — the UI must complete identity exchange first.
     */
    suspend fun sendText(peerId: String, text: String): MessageEntity {
        require(text.isNotBlank()) { "Message text must not be blank" }
        val peer = db.peerDao().getById(peerId)
            ?: throw IllegalStateException(
                "Unknown peer $peerId — discover the peer and exchange identity keys before chatting."
            )
        require(peer.x25519Public.isNotEmpty()) {
            "Peer $peerId has no X25519 public key yet — identity exchange has not completed."
        }

        val now = System.currentTimeMillis()
        val sealed = crypto.seal(identity, peer.x25519Public, text.toByteArray(Charsets.UTF_8))
        val unsigned = MessageEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = EnvelopeType.TEXT,
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

        val entity = MessageEntity(
            messageId = envelope.messageId,
            conversationPeerId = peerId,
            senderId = identity.peerId,
            recipientId = peerId,
            plaintext = text,
            timestampMs = now,
            expiryMs = envelope.expiryMs,
            deliveryState = DeliveryState.QUEUED.name,
            hopCount = 0,
            isMine = true
        )
        db.messageDao().insert(entity)
        upsertConversation(
            peerId = peerId,
            displayName = peer.displayName,
            preview = text,
            atMs = now,
            incrementUnread = false
        )
        dispatch(envelope)
        return entity
    }

    private suspend fun dispatch(envelope: MessageEnvelope) {
        // Router queues into the OutboxStore itself; if no router is wired
        // (e.g. relay service stopped), the caller can enqueue via
        // RoomOutboxStore directly — the message stays QUEUED either way.
        router?.send(envelope)
    }

    /** Called by the app layer for MeshEvent.MessageReceived addressed to us. */
    suspend fun handleIncoming(envelope: MessageEnvelope, plaintext: String) {
        if (db.seenDao().exists(envelope.messageId)) return
        db.seenDao().insert(
            com.p2pchat.messenger.data.entity.SeenEntity(envelope.messageId, System.currentTimeMillis())
        )
        db.messageDao().insert(
            MessageEntity(
                messageId = envelope.messageId,
                conversationPeerId = envelope.senderId,
                senderId = envelope.senderId,
                recipientId = envelope.recipientId,
                plaintext = plaintext,
                timestampMs = envelope.timestampMs,
                expiryMs = envelope.expiryMs,
                deliveryState = DeliveryState.DELIVERED.name,
                hopCount = envelope.hopCount,
                isMine = false
            )
        )
        val peer = db.peerDao().getById(envelope.senderId)
        upsertConversation(
            peerId = envelope.senderId,
            displayName = peer?.displayName ?: envelope.senderId.take(8),
            preview = plaintext,
            atMs = envelope.timestampMs,
            incrementUnread = true
        )
    }

    /** Called by the app layer for MeshEvent.AckReceived / Forwarded / Expired. */
    suspend fun updateDeliveryState(messageId: String, state: DeliveryState, hopCount: Int? = null) {
        if (hopCount != null) {
            db.messageDao().updateDeliveryStateAndHops(messageId, state.name, hopCount)
        } else {
            db.messageDao().updateDeliveryState(messageId, state.name)
        }
    }

    suspend fun deleteMessage(messageId: String) {
        db.messageDao().deleteById(messageId)
        db.outboxDao().deleteById(messageId)
    }

    suspend fun clearConversation(peerId: String) {
        db.messageDao().deleteByConversation(peerId)
        db.conversationDao().deleteById(peerId)
    }

    suspend fun markConversationRead(peerId: String) = db.conversationDao().clearUnread(peerId)

    suspend fun ensureConversation(peerId: String, displayName: String) {
        if (db.conversationDao().getById(peerId) == null) {
            db.conversationDao().upsert(
                ConversationEntity(peerId = peerId, displayName = displayName)
            )
        }
    }

    private suspend fun upsertConversation(
        peerId: String,
        displayName: String,
        preview: String,
        atMs: Long,
        incrementUnread: Boolean
    ) {
        val existing = db.conversationDao().getById(peerId)
        db.conversationDao().upsert(
            ConversationEntity(
                peerId = peerId,
                displayName = displayName,
                lastMessagePreview = preview.take(120),
                lastMessageMs = atMs,
                unreadCount = (existing?.unreadCount ?: 0) + if (incrementUnread) 1 else 0
            )
        )
    }
}
