package com.p2pchat.messenger.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A chat message in a local conversation.
 *
 * [plaintext] is stored decrypted ONLY for the user's own conversations
 * (messages they sent or that were addressed to them and decrypted locally).
 * Relayed envelopes for other people are never stored here — they live,
 * still encrypted, in [OutboxEntity] until forwarded or expired.
 *
 * [deliveryState] stores a `com.p2pchat.messenger.mesh.DeliveryState` name:
 * QUEUED, RELAYED, DELIVERED, EXPIRED or FAILED. RELAYED means a relay
 * accepted the envelope; it is NOT confirmed delivery to the recipient.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationPeerId"])]
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationPeerId: String,
    val senderId: String,
    val recipientId: String,
    val plaintext: String,
    val timestampMs: Long,
    val expiryMs: Long,
    val deliveryState: String,
    val hopCount: Int = 0,
    val isMine: Boolean
)
