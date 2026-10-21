package com.p2pchat.messenger.data

import com.p2pchat.messenger.core.codec.EnvelopeCodec
import com.p2pchat.messenger.core.model.MessageEnvelope
import com.p2pchat.messenger.data.dao.OutboxDao
import com.p2pchat.messenger.data.entity.OutboxEntity
import com.p2pchat.messenger.mesh.OutboxStore
import kotlinx.coroutines.flow.Flow

/**
 * Room-backed [OutboxStore]: the mesh engine's persistent store-and-forward
 * queue. Envelopes are serialized with [EnvelopeCodec] and stored as BLOBs;
 * their payloads remain end-to-end encrypted at rest.
 *
 * TODO(contract): OutboxStore method names come from the shared mesh
 * contract. If mesh-engine finalises different names, only the `override`
 * signatures in this adapter need to change — the DAO logic is stable.
 */
class RoomOutboxStore(
    private val outboxDao: OutboxDao
) : OutboxStore {

    override suspend fun enqueue(envelope: MessageEnvelope) {
        outboxDao.upsert(
            OutboxEntity(
                messageId = envelope.messageId,
                envelopeBytes = EnvelopeCodec.serialize(envelope),
                attempts = 0,
                nextAttemptAtMs = 0L,
                createdMs = System.currentTimeMillis()
            )
        )
    }

    override suspend fun due(nowMs: Long, limit: Int): List<MessageEnvelope> =
        outboxDao.getDue(nowMs, limit).mapNotNull { entity ->
            runCatching { EnvelopeCodec.deserialize(entity.envelopeBytes) }.getOrNull()
        }

    override suspend fun all(): List<MessageEnvelope> =
        outboxDao.getAll().mapNotNull { entity ->
            runCatching { EnvelopeCodec.deserialize(entity.envelopeBytes) }.getOrNull()
        }

    override suspend fun remove(messageId: String) {
        outboxDao.deleteById(messageId)
    }

    override suspend fun recordAttempt(messageId: String, nextAttemptAtMs: Long) {
        outboxDao.recordAttempt(messageId, nextAttemptAtMs)
    }

    override suspend fun pendingCount(): Int = outboxDao.count()

    override fun observePendingCount(): Flow<Int> = outboxDao.observeCount()

    override suspend fun clearExpired(nowMs: Long) {
        // Expiry itself is carried inside each envelope; the router decides
        // which envelopes are stale. Here we only drop queue rows whose
        // envelope can no longer be deserialized / has passed its expiry
        // when the router asks for a sweep via due().
        val stale = outboxDao.getAll().filter { entity ->
            val envelope = runCatching { EnvelopeCodec.deserialize(entity.envelopeBytes) }.getOrNull()
            envelope == null || envelope.expiryMs in 1..nowMs
        }
        stale.forEach { outboxDao.deleteById(it.messageId) }
    }
}
