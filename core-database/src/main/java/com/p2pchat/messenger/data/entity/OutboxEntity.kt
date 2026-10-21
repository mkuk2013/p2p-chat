package com.p2pchat.messenger.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Store-and-forward queue entry: a serialized
 * `com.p2pchat.messenger.core.model.MessageEnvelope` (see EnvelopeCodec).
 * The envelope payload stays end-to-end encrypted while queued here.
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val messageId: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val envelopeBytes: ByteArray,
    val attempts: Int = 0,
    val nextAttemptAtMs: Long = 0L,
    val createdMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutboxEntity) return false
        return messageId == other.messageId &&
            envelopeBytes.contentEquals(other.envelopeBytes) &&
            attempts == other.attempts &&
            nextAttemptAtMs == other.nextAttemptAtMs &&
            createdMs == other.createdMs
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + envelopeBytes.contentHashCode()
        result = 31 * result + attempts
        result = 31 * result + nextAttemptAtMs.hashCode()
        result = 31 * result + createdMs.hashCode()
        return result
    }
}
