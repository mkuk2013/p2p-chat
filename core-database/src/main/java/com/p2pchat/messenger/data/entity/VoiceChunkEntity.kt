package com.p2pchat.messenger.data.entity

import androidx.room.Entity

/**
 * One chunk of a voice-note blob (see [VoiceTransferEntity]).
 *
 * Outgoing rows track the mesh envelope carrying the chunk:
 * [envelopeMessageId] is set, [payload] stays null (the bytes are
 * re-split from the kept recording on retry), and [state] mirrors the
 * router's per-envelope DeliveryState (QUEUED / RELAYED / DELIVERED /
 * EXPIRED).
 *
 * Incoming rows hold the received audio bytes in [payload] until the
 * blob completes ([state] = [STATE_RECEIVED]); they are deleted once the
 * file has been assembled and verified, so partial blobs never leak into
 * the conversation as playable messages.
 */
@Entity(
    tableName = "voice_chunks",
    primaryKeys = ["blobId", "chunkIndex"]
)
data class VoiceChunkEntity(
    val blobId: String,
    val chunkIndex: Int,
    val envelopeMessageId: String? = null,
    val payload: ByteArray? = null,
    val state: String
) {
    companion object {
        const val STATE_RECEIVED: String = "RECEIVED"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceChunkEntity) return false
        return blobId == other.blobId && chunkIndex == other.chunkIndex &&
            envelopeMessageId == other.envelopeMessageId &&
            payload.contentEquals(other.payload) && state == other.state
    }

    override fun hashCode(): Int {
        var r = blobId.hashCode()
        r = 31 * r + chunkIndex
        r = 31 * r + (envelopeMessageId?.hashCode() ?: 0)
        r = 31 * r + payload.contentHashCode()
        r = 31 * r + state.hashCode()
        return r
    }
}
