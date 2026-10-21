package com.p2pchat.messenger.mesh

import com.p2pchat.messenger.core.model.MessageEnvelope
import java.util.concurrent.ConcurrentHashMap

/** An envelope waiting for (re)transmission, with its retry bookkeeping. */
data class QueuedMessage(
    val envelope: MessageEnvelope,
    val attempts: Int = 0,
    val nextAttemptAtMs: Long = 0L,
) {
    val messageId: String get() = envelope.messageId
}

/**
 * Persistent store-and-forward queue for outgoing envelopes.
 *
 * The production implementation lives in the app/data layer backed by Room
 * (so the queue survives process death); [InMemoryOutboxStore] below is the
 * reference implementation used by unit tests and previews. Both honour the
 * same contract:
 *  - [pending] returns only envelopes that are unexpired at `nowMs` AND whose
 *    `nextAttemptAtMs <= nowMs`, oldest first;
 *  - [incrementAttempts] applies the shared exponential backoff
 *    (see [OutboxBackoff]) so behaviour matches across implementations.
 */
interface OutboxStore {

    suspend fun insert(envelope: MessageEnvelope)

    suspend fun pending(nowMs: Long): List<MessageEnvelope>

    /** Full queue entries (envelope + attempts + next attempt), oldest first. */
    suspend fun queued(): List<QueuedMessage>

    suspend fun remove(messageId: String)

    /** Record one more failed/deferred attempt and schedule the next one. */
    suspend fun incrementAttempts(messageId: String)

    /** Delete every envelope whose expiry is before [nowMs]. @return count removed. */
    suspend fun purgeExpired(nowMs: Long): Int

    suspend fun contains(messageId: String): Boolean

    suspend fun size(): Int
}

/** Exponential retry backoff shared by all [OutboxStore] implementations:
 * 5 s, 10 s, 20 s, 40 s … capped at 5 minutes. */
object OutboxBackoff {
    const val BASE_MS: Long = 5_000L
    const val MAX_MS: Long = 5L * 60_000L

    fun nextAttemptAt(nowMs: Long, attemptsAfterIncrement: Int): Long {
        val shift = (attemptsAfterIncrement - 1).coerceIn(0, 20)
        val delay = minOf(MAX_MS, BASE_MS * (1L shl shift))
        return nowMs + delay
    }
}

/**
 * In-memory [OutboxStore] for tests and previews. NOT durable — the app uses
 * the Room-backed implementation so pending messages survive process death.
 */
class InMemoryOutboxStore(
    private val clock: () -> Long = { System.currentTimeMillis() },
) : OutboxStore {

    private val entries = ConcurrentHashMap<String, QueuedMessage>()
    private val lock = Any()

    override suspend fun insert(envelope: MessageEnvelope) {
        synchronized(lock) {
            // Re-inserting the same ID keeps the furthest-progressed retry state.
            val existing = entries[envelope.messageId]
            entries[envelope.messageId] = existing?.copy(envelope = envelope)
                ?: QueuedMessage(envelope)
        }
    }

    override suspend fun pending(nowMs: Long): List<MessageEnvelope> =
        queued().filter { it.envelope.expiryMs > nowMs && it.nextAttemptAtMs <= nowMs }
            .map { it.envelope }

    override suspend fun queued(): List<QueuedMessage> =
        synchronized(lock) {
            entries.values.sortedBy { it.envelope.timestampMs }
        }

    override suspend fun remove(messageId: String) {
        entries.remove(messageId)
    }

    override suspend fun incrementAttempts(messageId: String) {
        synchronized(lock) {
            val current = entries[messageId] ?: return
            val attempts = current.attempts + 1
            entries[messageId] = current.copy(
                attempts = attempts,
                nextAttemptAtMs = OutboxBackoff.nextAttemptAt(clock(), attempts),
            )
        }
    }

    override suspend fun purgeExpired(nowMs: Long): Int {
        var removed = 0
        synchronized(lock) {
            val iterator = entries.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value.envelope.expiryMs <= nowMs) {
                    iterator.remove()
                    removed++
                }
            }
        }
        return removed
    }

    override suspend fun contains(messageId: String): Boolean = entries.containsKey(messageId)

    override suspend fun size(): Int = entries.size
}
