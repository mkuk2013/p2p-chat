package com.p2pchat.messenger.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One voice-note blob transfer, in either direction.
 *
 * The sender creates the row when the note is queued ([isMine] = true);
 * the receiver creates it when the first chunk arrives. [state] is one of
 * [STATE_ACTIVE] (chunks still moving), [STATE_COMPLETE] (blob assembled
 * + hash-verified on receive, or every chunk ACKed on send) or
 * [STATE_FAILED] (a chunk expired/failed before completion — the note can
 * be retried, which re-queues only the missing chunks). [completedChunks]
 * counts ACKed chunks when sending and stored chunks when receiving, so
 * the UI progress bar is always the truth, never an estimate.
 *
 * [filePath] is the local .m4a: the sender's kept recording (the source
 * for retries), set at creation; the receiver's reassembled file, set on
 * completion.
 */
@Entity(tableName = "voice_transfers")
data class VoiceTransferEntity(
    @PrimaryKey val blobId: String,
    val conversationPeerId: String,
    val isMine: Boolean,
    val totalChunks: Int,
    val completedChunks: Int = 0,
    val totalBytes: Long,
    val sha256Hex: String,
    val durationMs: Int,
    val state: String = STATE_ACTIVE,
    val filePath: String? = null,
    val createdAtMs: Long
) {
    companion object {
        const val STATE_ACTIVE: String = "ACTIVE"
        const val STATE_COMPLETE: String = "COMPLETE"
        const val STATE_FAILED: String = "FAILED"
    }
}
