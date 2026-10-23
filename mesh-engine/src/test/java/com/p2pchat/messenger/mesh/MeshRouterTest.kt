package com.p2pchat.messenger.mesh

import com.p2pchat.messenger.core.codec.EnvelopeCodec
import com.p2pchat.messenger.core.model.EnvelopeType
import com.p2pchat.messenger.core.model.MessageEnvelope
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Mesh protocol tests over an in-memory fake network.
 *
 * The fake transport delivers bytes synchronously into the target router,
 * so a full A→B→C→D flood and D→C→B→A ACK return complete inside one
 * `sendText` call — assertions can be made immediately afterwards.
 */
class MeshRouterTest {

    // ------------------------------------------------------------ fake network

    private class FakeTransport(
        private val ownerId: String,
        private val network: FakeNetwork,
    ) : MeshTransport {
        var sendAttempts: Int = 0
            private set
        val broadcasts = mutableListOf<ByteArray>()

        override fun connectedPeerIds(): Set<String> =
            network.adjacency[ownerId]?.toSet() ?: emptySet()

        override suspend fun sendTo(peerId: String, bytes: ByteArray): Boolean {
            sendAttempts++
            val target = network.routers[peerId] ?: return false
            if (peerId !in connectedPeerIds()) return false
            target.onIncomingBytes(ownerId, bytes)
            return true
        }

        override fun broadcastExcept(excludePeerId: String?, bytes: ByteArray) {
            broadcasts.add(bytes)
        }
    }

    private class FakeNetwork {
        val routers = mutableMapOf<String, MeshRouter>()
        val transports = mutableMapOf<String, FakeTransport>()
        val outboxes = mutableMapOf<String, InMemoryOutboxStore>()
        val adjacency = mutableMapOf<String, LinkedHashSet<String>>()

        fun node(peerId: String): MeshRouter {
            val transport = FakeTransport(peerId, this)
            val outbox = InMemoryOutboxStore()
            // Pass-through "crypto" for protocol tests: ciphertext IS the
            // plaintext, so the router's decryptor just returns it. Real
            // sealing is tested in :core-crypto.
            val router = MeshRouter(
                localPeerId = peerId,
                transport = transport,
                outbox = outbox,
                decryptor = { env -> env.ciphertext },
            )
            routers[peerId] = router
            transports[peerId] = transport
            outboxes[peerId] = outbox
            adjacency.putIfAbsent(peerId, linkedSetOf())
            return router
        }

        fun connect(a: String, b: String) {
            adjacency.getValue(a).add(b)
            adjacency.getValue(b).add(a)
        }

        /** Chain topology: ids[0] — ids[1] — ids[2] — … (ends cannot see each other). */
        fun chain(vararg ids: String) {
            ids.forEach { node(it) }
            for (i in 0 until ids.size - 1) connect(ids[i], ids[i + 1])
        }
    }

    private lateinit var network: FakeNetwork

    @Before
    fun setUp() {
        network = FakeNetwork()
    }

    private fun textEnvelope(
        from: String,
        to: String,
        body: String,
        ttl: Int = MessageEnvelope.DEFAULT_TTL,
        expiryMs: Long = System.currentTimeMillis() + 3_600_000L,
        messageId: String = UUID.randomUUID().toString(),
    ): MessageEnvelope = MessageEnvelope(
        messageId = messageId,
        type = EnvelopeType.TEXT,
        senderId = from,
        recipientId = to,
        timestampMs = System.currentTimeMillis(),
        expiryMs = expiryMs,
        ttl = ttl,
        hopCount = 0,
        ciphertext = body.toByteArray(Charsets.UTF_8),
    )

    private fun MeshRouter.received(): List<MeshEvent.MessageReceived> =
        events.replayCache.filterIsInstance<MeshEvent.MessageReceived>()

    private fun MeshRouter.acks(): List<MeshEvent.AckReceived> =
        events.replayCache.filterIsInstance<MeshEvent.AckReceived>()

    private fun MeshRouter.forwardedIds(): List<String> =
        events.replayCache.filterIsInstance<MeshEvent.Forwarded>().map { it.messageId }

    // ------------------------------------------------------------ the tests

    @Test
    fun `message travels A to D through B and C who cannot read it`() = runBlocking {
        network.chain("A", "B", "C", "D")
        val a = network.routers.getValue("A")
        val d = network.routers.getValue("D")

        // A and D are NOT directly connected — delivery must be multi-hop.
        assertFalse(network.adjacency.getValue("A").contains("D"))

        val envelope = textEnvelope("A", "D", "hello mesh")
        val accepted = a.sendText("D", envelope)

        assertTrue(accepted)
        // D received exactly one copy, with the body intact.
        assertEquals(1, d.received().size)
        assertEquals(
            "hello mesh",
            d.received().single().plaintext?.toString(Charsets.UTF_8),
        )
        // Relays B and C forwarded the sealed envelope but never "received"
        // it as a message (they cannot read what they carry).
        assertTrue(network.routers.getValue("B").received().isEmpty())
        assertTrue(network.routers.getValue("C").received().isEmpty())
        // Both relays did forward this message ID.
        assertTrue(network.routers.getValue("B").forwardedIds().contains(envelope.messageId))
        assertTrue(network.routers.getValue("C").forwardedIds().contains(envelope.messageId))
    }

    @Test
    fun `A is DELIVERED only after D's ACK travels back`() = runBlocking {
        network.chain("A", "B", "C", "D")
        val a = network.routers.getValue("A")

        val envelope = textEnvelope("A", "D", "ack me")
        a.sendText("D", envelope)

        assertEquals(1, a.acks().size)
        assertEquals(envelope.messageId, a.acks().single().originalMessageId)
        assertEquals("D", a.acks().single().fromPeerId)
        assertEquals(DeliveryState.DELIVERED, a.deliveryStateOf(envelope.messageId))
        // Delivered messages leave the outbox.
        assertEquals(0, network.outboxes.getValue("A").size())
    }

    @Test
    fun `relay acceptance is NOT delivery - broken route stays RELAYED and queued`() = runBlocking {
        // A—B only. D does not even exist on the network yet.
        network.node("A"); network.node("B")
        network.connect("A", "B")
        val a = network.routers.getValue("A")

        val envelope = textEnvelope("A", "D", "anyone there?")
        val accepted = a.sendText("D", envelope)

        assertTrue("B accepted the envelope for relay", accepted)
        assertEquals(DeliveryState.RELAYED, a.deliveryStateOf(envelope.messageId))
        assertNotEquals(DeliveryState.DELIVERED, a.deliveryStateOf(envelope.messageId))
        assertTrue(a.acks().isEmpty())
        // Still queued at A — a relay accepting bytes removed nothing.
        assertEquals(1, network.outboxes.getValue("A").size())
        // And B stored it in its relay queue (store-and-forward), because
        // B currently has no onward route either.
        assertEquals(1, network.outboxes.getValue("B").size())
    }

    @Test
    fun `store-and-forward - relay queue delivers when the route appears`() = runBlocking {
        network.node("A"); network.node("B")
        network.connect("A", "B")
        val a = network.routers.getValue("A")
        val b = network.routers.getValue("B")

        val envelope = textEnvelope("A", "D", "wait for the route")
        a.sendText("D", envelope)
        assertEquals(DeliveryState.RELAYED, a.deliveryStateOf(envelope.messageId))

        // The rest of the chain shows up later (phones come into range).
        network.node("C"); network.node("D")
        network.connect("B", "C"); network.connect("C", "D")

        // B retries its relay queue — the message continues to D and D's
        // ACK flows back through C and B to A.
        b.flushOutbox()

        val d = network.routers.getValue("D")
        assertEquals(1, d.received().size)
        assertEquals(DeliveryState.DELIVERED, a.deliveryStateOf(envelope.messageId))
        assertEquals(0, network.outboxes.getValue("A").size())
        // B's relay copy was dropped when D's ACK passed through it.
        assertEquals(0, network.outboxes.getValue("B").size())
    }

    @Test
    fun `duplicate copies are suppressed at destination and at relays`() = runBlocking {
        network.chain("A", "B", "C", "D")
        val d = network.routers.getValue("D")

        val envelope = textEnvelope("A", "D", "only once")
        val bytes = EnvelopeCodec.serialize(envelope)

        // Same bytes arrive at D twice (e.g. via two different relays).
        d.onIncomingBytes("C", bytes)
        d.onIncomingBytes("C", bytes)
        assertEquals(1, d.received().size)

        // Same bytes arrive at relay B twice: B forwards only the first.
        val b = network.routers.getValue("B")
        val c = network.routers.getValue("C")
        val relayEnvelope = textEnvelope("A", "D", "relay once")
        val relayBytes = EnvelopeCodec.serialize(relayEnvelope)
        val cReceivedBefore = c.forwardedIds().size + c.received().size
        b.onIncomingBytes("A", relayBytes)
        val afterFirst = network.transports.getValue("B").sendAttempts
        b.onIncomingBytes("A", relayBytes)
        val afterSecond = network.transports.getValue("B").sendAttempts
        assertEquals("duplicate must not be forwarded again", afterFirst, afterSecond)
        assertTrue(cReceivedBefore >= 0) // (kept simple; sendAttempts is the real assertion)
    }

    @Test
    fun `TTL exhaustion stops forwarding before the destination`() = runBlocking {
        network.chain("A", "B", "C", "D")
        val a = network.routers.getValue("A")
        val d = network.routers.getValue("D")

        // ttl = 1 remaining hop: A→B is the sender flood, B→C consumes the
        // last hop, C must not forward to D.
        val envelope = textEnvelope("A", "D", "short lived", ttl = 1)
        a.sendText("D", envelope)

        assertTrue(d.received().isEmpty())
        assertTrue(network.routers.getValue("C").received().isEmpty())
        assertNotEquals(DeliveryState.DELIVERED, a.deliveryStateOf(envelope.messageId))
    }

    @Test
    fun `expired envelopes are dropped, not forwarded or delivered`() = runBlocking {
        network.chain("A", "B", "C", "D")
        val b = network.routers.getValue("B")
        val d = network.routers.getValue("D")

        val envelope = textEnvelope(
            "A", "D", "too late",
            expiryMs = System.currentTimeMillis() - 1_000L,
        )
        b.onIncomingBytes("A", EnvelopeCodec.serialize(envelope))

        assertTrue(d.received().isEmpty())
        assertTrue(
            b.events.replayCache.filterIsInstance<MeshEvent.Expired>()
                .any { it.messageId == envelope.messageId },
        )
    }

    @Test
    fun `loop prevention - triangle flood terminates and never echoes to sender`() = runBlocking {
        // Fully connected triangle A—B—C, recipient Z does not exist:
        // worst case for flood loops. Without dedup + split horizon this
        // would bounce forever (this test would hang / overflow).
        network.node("A"); network.node("B"); network.node("C")
        network.connect("A", "B"); network.connect("B", "C"); network.connect("A", "C")
        val a = network.routers.getValue("A")

        val envelope = textEnvelope("A", "Z", "loop bait", ttl = 10)
        a.sendText("Z", envelope)

        // Total radio sends across the whole network stay tiny and bounded:
        // A floods to B,C (2); B forwards to C (1); C forwards to A (1,
        // dropped by A as its own / already seen). Nothing more.
        val totalSends = network.transports.values.sumOf { it.sendAttempts }
        assertTrue("flood must terminate, was $totalSends sends", totalSends <= 4)
        // Nobody "received" a message addressed to Z.
        network.routers.values.forEach { assertTrue(it.received().isEmpty()) }
        // A never treats its own returning message as new mail.
        assertTrue(a.acks().isEmpty())
    }

    @Test
    fun `malformed bytes are dropped silently`() = runBlocking {
        network.chain("A", "B")
        val b = network.routers.getValue("B")
        b.onIncomingBytes("A", byteArrayOf(1, 2, 3))
        b.onIncomingBytes("A", ByteArray(0))
        assertTrue(b.received().isEmpty())
    }

    @Test
    fun `flushOutbox with no neighbours keeps message queued and backs off`() = runBlocking {
        network.node("A")
        val a = network.routers.getValue("A")
        val outbox = network.outboxes.getValue("A")

        val envelope = textEnvelope("A", "D", "isolated")
        val accepted = a.sendText("D", envelope)
        assertFalse(accepted)
        assertEquals(DeliveryState.QUEUED, a.deliveryStateOf(envelope.messageId))

        a.flushOutbox()
        assertEquals(1, outbox.size())
        val queued = outbox.queued().single()
        assertEquals(1, queued.attempts)
        assertTrue("backoff must schedule a future attempt", queued.nextAttemptAtMs > queued.envelope.timestampMs)
    }

    private fun signalEnvelope(
        from: String,
        to: String,
        body: String,
        messageId: String = UUID.randomUUID().toString(),
    ): MessageEnvelope = MessageEnvelope(
        messageId = messageId,
        type = EnvelopeType.SIGNAL,
        senderId = from,
        recipientId = to,
        timestampMs = System.currentTimeMillis(),
        expiryMs = System.currentTimeMillis() + 3_600_000L,
        ttl = MessageEnvelope.DEFAULT_TTL,
        hopCount = 0,
        ciphertext = body.toByteArray(Charsets.UTF_8),
    )

    @Test
    fun `SIGNAL is delivered decrypted, never ACKed and never outbox-queued`() = runBlocking {
        network.node("A"); network.node("B")
        network.connect("A", "B")
        val a = network.routers.getValue("A")
        val b = network.routers.getValue("B")

        val envelope = signalEnvelope("A", "B", "WIFICALL:invite")
        val accepted = a.sendSignal("B", envelope)

        assertTrue(accepted)
        assertEquals(1, b.received().size)
        assertEquals(EnvelopeType.SIGNAL, b.received().single().envelope.type)
        assertEquals("WIFICALL:invite", b.received().single().plaintext?.toString(Charsets.UTF_8))
        // No ACK travels back for a signal — call state machines own
        // their retries; an ACK would also re-ring nothing but noise.
        assertTrue(a.acks().isEmpty())
        // Fire-and-forget: neither side keeps a copy in the outbox.
        assertEquals(0, network.outboxes.getValue("A").size())
        assertEquals(0, network.outboxes.getValue("B").size())
    }

    @Test
    fun `SIGNAL relays multi-hop to its recipient`() = runBlocking {
        network.chain("A", "B", "C")
        val a = network.routers.getValue("A")
        val c = network.routers.getValue("C")

        val accepted = a.sendSignal("C", signalEnvelope("A", "C", "WIFICALL:invite"))

        assertTrue(accepted)
        assertEquals(1, c.received().size)
        assertEquals(EnvelopeType.SIGNAL, c.received().single().envelope.type)
        // The relay in the middle never "receives" the signal.
        assertTrue(network.routers.getValue("B").received().isEmpty())
    }
}
