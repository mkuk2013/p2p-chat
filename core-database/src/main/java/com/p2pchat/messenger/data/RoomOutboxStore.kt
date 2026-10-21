package com.p2pchat.messenger.data

import com.p2pchat.messenger.core.codec.EnvelopeCodec
import com.p2pchat.messenger.core.model.MessageEnvelope
import com.p2pchat.messenger.data.dao.OutboxDao
import com.p2pchat.messenger.data.entity.OutboxEntity
import com.p2pchat.messenger.mesh.OutboxBackoff
import com.p2pchat.messenger.mesh.OutboxStore
import com.p2pchat.messenger.mesh.QueuedMessage
import kotlinx.coroutines.flow.Flow

/**
 * Room-backed [OutboxStore]: the mesh engine's persistent store-and-forward
 * queue. Envelopes are serialized with [EnvelopeCodec] and stored as BLOBs;
 * their payloads remain end-to-end encrypted at rest.
 *
 * Contract (matches mesh-engine's [OutboxStore] exactly):
 *  - [pending]: unexpired + due (nextAttemptAtMs <= now), oldest first;
 *  - [incrementAttempts]: shared exponential backoff ([OutboxBackoff]).
 */
class RoomOutboxStore(
    private val outboxDao: OutboxDao
) : OutboxStore {

    override suspend fun insert(envelope: MessageEnvelope) {
        val bytes = EnvelopeCodec.serialize(envelope)
        val existing = outboxDao.getById(envelope.messageId)
        if (existing != null) {
            // Keep retry progress, refresh the stored envelope bytes.
            outboxDao.upsert(existing.copy(envelopeBytes = bytes))
        } else {
            outboxDao.insert(
                OutboxEntity(
                    messageId = envelope.messageId,
                    envelopeBytes = bytes,
                    attempts = 0,
                    nextAttemptAtMs = 0L,
                    createdMs = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun pending(nowMs: Long): List<MessageEnvelope> =
        outboxDao.getDue(nowMs, MAX_DUE_BATCH).mapNotNull { entity ->
            runCatching { EnvelopeCodec.deserialize(entity.envelopeBytes) }.getOrNull()
        }.filter { it.expiryMs > nowMs }

    override suspend fun queued(): List<QueuedMessage> =
        outboxDao.getAll().mapNotNull { entity ->
            val envelope = runCatching {
                EnvelopeCodec.deserialize(entity.envelopeBytes)
            }.getOrNull() ?: return@mapNotNull null
            QueuedMessage(
                envelope = envelope,
                attempts = entity.attempts,
                nextAttemptAtMs = entity.nextAttemptAtMs
            )
        }.sortedBy { it.envelope.timestampMs }

    override suspend fun remove(messageId: String) = outboxDao.deleteById(messageId)

    override suspend fun incrementAttempts(messageId: String) {
        val row = outboxDao.getById(messageId) ?: return
        outboxDao.recordAttempt(
            messageId,
            OutboxBackoff.nextAttemptAt(System.currentTimeMillis(), row.attempts + 1)
        )
    }

    override suspend fun purgeExpired(nowMs: Long): Int {
        val stale = outboxDao.getAll().filter { entity ->
            val envelope = runCatching {
                EnvelopeCodec.deserialize(entity.envelopeBytes)
            }.getOrNull()
            envelope == null || envelope.expiryMs <= nowMs
        }
        stale.forEach { outboxDao.deleteById(it.messageId) }
        return stale.size
    }

    override suspend fun contains(messageId: String): Boolean =
        outboxDao.getById(messageId) != null

    override suspend fun size(): Int = outboxDao.count()

    /** Extra convenience (not part of the mesh interface): live pending count for UI. */
    fun observePendingCount(): Flow<Int> = outboxDao.observeCount()

    private companion object {
        const val MAX_DUE_BATCH: Int = 500
    }
}
