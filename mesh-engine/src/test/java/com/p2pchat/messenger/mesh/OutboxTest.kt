package com.p2pchat.messenger.mesh

import com.p2pchat.messenger.core.model.MessageEnvelope
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {

    private var fakeNow = 1_700_000_000_000L

    private fun envelope(
        id: String = UUID.randomUUID().toString(),
        timestampMs: Long = fakeNow,
        expiryMs: Long = fakeNow + 3_600_000L,
    ): MessageEnvelope = MessageEnvelope(
        messageId = id,
        senderId = "sender",
        recipientId = "recipient",
        timestampMs = timestampMs,
        expiryMs = expiryMs,
    )

    private fun store() = InMemoryOutboxStore(clock = { fakeNow })

    @Test
    fun `insert then pending returns oldest first`() = runBlocking {
        val outbox = store()
        val newer = envelope(id = "newer", timestampMs = fakeNow + 1_000L)
        val older = envelope(id = "older", timestampMs = fakeNow)
        outbox.insert(newer)
        outbox.insert(older)
        assertEquals(listOf("older", "newer"), outbox.pending(fakeNow + 2_000L).map { it.messageId })
        assertTrue(outbox.contains("older"))
        assertEquals(2, outbox.size())
    }

    @Test
    fun `pending excludes expired envelopes`() = runBlocking {
        val outbox = store()
        outbox.insert(envelope(id = "live"))
        outbox.insert(envelope(id = "dead", expiryMs = fakeNow - 1L))
        assertEquals(listOf("live"), outbox.pending(fakeNow).map { it.messageId })
    }

    @Test
    fun `remove deletes by id`() = runBlocking {
        val outbox = store()
        outbox.insert(envelope(id = "x"))
        outbox.remove("x")
        assertFalse(outbox.contains("x"))
        assertEquals(0, outbox.size())
    }

    @Test
    fun `incrementAttempts applies exponential backoff and hides until due`() = runBlocking {
        val outbox = store()
        outbox.insert(envelope(id = "retry"))
        outbox.incrementAttempts("retry")

        val queued = outbox.queued().single()
        assertEquals(1, queued.attempts)
        assertEquals(fakeNow + OutboxBackoff.BASE_MS, queued.nextAttemptAtMs)
        // Not due yet at the same instant…
        assertTrue(outbox.pending(fakeNow).isEmpty())
        // …due once the backoff has passed.
        assertEquals(1, outbox.pending(fakeNow + OutboxBackoff.BASE_MS).size)

        // Second failure doubles the delay (10s).
        fakeNow += OutboxBackoff.BASE_MS
        outbox.incrementAttempts("retry")
        assertEquals(2, outbox.queued().single().attempts)
        assertEquals(fakeNow + 2 * OutboxBackoff.BASE_MS, outbox.queued().single().nextAttemptAtMs)
    }

    @Test
    fun `purgeExpired removes expired and reports the count`() = runBlocking {
        val outbox = store()
        outbox.insert(envelope(id = "a", expiryMs = fakeNow - 5L))
        outbox.insert(envelope(id = "b", expiryMs = fakeNow + 5_000L))
        outbox.insert(envelope(id = "c", expiryMs = fakeNow - 1L))
        assertEquals(2, outbox.purgeExpired(fakeNow))
        assertEquals(listOf("b"), outbox.queued().map { it.messageId })
    }

    @Test
    fun `backoff is capped at five minutes`() {
        val at = OutboxBackoff.nextAttemptAt(0L, attemptsAfterIncrement = 30)
        assertEquals(OutboxBackoff.MAX_MS, at)
    }
}
