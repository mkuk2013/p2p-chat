package com.p2pchat.messenger.data.entity

import androidx.room.ColumnInfo
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
 *
 * Voice notes: [kind] is [KIND_TEXT] or [KIND_VOICE]. A voice note has an
 * empty [plaintext], a [mediaPath] pointing at the local .m4a file (the
 * sender's recording, or the reassembled file for a received note), a
 * [durationMs], and a [blobId] joining it to its chunk transfer in
 * `voice_transfers` / `voice_chunks`. For an outgoing note the blobId IS
 * the messageId; chunk progress lives in the transfer tables, and this
 * row's [deliveryState] only reaches DELIVERED when every chunk has been
 * ACKed by the recipient device.
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
    val isMine: Boolean,
    @ColumnInfo(defaultValue = "TEXT")
    val kind: String = KIND_TEXT,
    val mediaPath: String? = null,
    @ColumnInfo(defaultValue = "0")
    val durationMs: Int = 0,
    val blobId: String? = null
) {
    companion object {
        const val KIND_TEXT: String = "TEXT"
        const val KIND_VOICE: String = "VOICE"

        /** Conversation-list preview for a voice note (WhatsApp-style). */
        const val VOICE_PREVIEW: String = "🎤 Voice message"
    }
}
