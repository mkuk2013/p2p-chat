package com.p2pchat.messenger.mesh

import com.p2pchat.messenger.core.blob.BlobChunkCodec
import com.p2pchat.messenger.core.model.EnvelopeType
import com.p2pchat.messenger.core.model.MessageEnvelope
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLOB envelopes (voice-note chunks) through the router: they must be
 * decrypted + emitted at the recipient and ACKed per chunk, exactly like
 * TEXT, and relays must stay blind. Uses the same synchronous fake
 * network pattern as [MeshRouterTest].
 */
class MeshRouterBlobTest {

    private class FakeTransport(
        private val ownerId: String,
        private val network: FakeNetwork,
    ) : MeshTransport {
        override fun connectedPeerIds(): Set<String> =
            network.adjacency[ownerId]?.toSet() ?: emptySet()

        override suspend fun sendTo(peerId: String, bytes: ByteArray): Boolean {
            val target = network.routers[peerId] ?: return false
            if (peerId !in connectedPeerIds()) return false
            target.onIncomingBytes(ownerId, bytes)
            return true
        }

        override fun broadcastExcept(excludePeerId: String?, bytes: ByteArray) = Unit
    }

    private class FakeNetwork {
        val routers = mutableMapOf<String, MeshRouter>()
        val adjacency = mutableMapOf<String, LinkedHashSet<String>>()

        fun node(peerId: String): MeshRouter {
            val router = MeshRouter(
                localPeerId = peerId,
                transport = FakeTransport(peerId, this),
                outbox = InMemoryOutboxStore(),
                decryptor = { env -> env.ciphertext },
            )
            routers[peerId] = router
            adjacency.putIfAbsent(peerId, linkedSetOf())
            return router
        }

        fun connect(a: String, b: String) {
            adjacency.getValue(a).add(b)
            adjacency.getValue(b).add(a)
        }

        fun chain(vararg ids: String) {
            ids.forEach { node(it) }
            for (i in 0 until ids.lastIndex) connect(ids[i], ids[i + 1])
        }
    }

    private fun MeshRouter.received() =
        events.replayCache.filterIsInstance<MeshEvent.MessageReceived>()

    private fun blobEnvelope(from: String, to: String, frame: ByteArray): MessageEnvelope =
        MessageEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = EnvelopeType.BLOB,
            senderId = from,
            recipientId = to,
            timestampMs = System.currentTimeMillis(),
            expiryMs = System.currentTimeMillis() + 60_000,
            ttl = MessageEnvelope.DEFAULT_TTL,
            hopCount = 0,
            ciphertext = frame,
        )

    @Test
    fun `blob chunks are delivered and ACKed per chunk like text`() = runBlocking {
        val network = FakeNetwork()
        network.chain("A", "B")
        val a = network.routers.getValue("A")
        val b = network.routers.getValue("B")

        val audio = ByteArray(20_000).also { Random(3).nextBytes(it) }
        val frames = BlobChunkCodec.split("note-1", audio, 12_000, "audio/mp4")
        assertEquals(3, frames.size)

        val chunkIds = mutableListOf<String>()
        for (frame in frames) {
            val env = blobEnvelope("A", "B", frame)
            chunkIds += env.messageId
            a.sendText("B", env)
        }

        // B received every chunk with its frame intact.
        val receivedFrames = b.received().map { it.plaintext!! }
        assertEquals(3, receivedFrames.size)
        frames.zip(receivedFrames).forEach { (sent, got) -> assertArrayEquals(sent, got) }

        // Every chunk envelope is DELIVERED at A via B's per-chunk ACKs.
        chunkIds.forEach { id ->
            assertEquals(DeliveryState.DELIVERED, a.deliveryStateOf(id))
        }
    }

    @Test
    fun `blob chunk crosses a blind relay and its ACK returns`() = runBlocking {
        val network = FakeNetwork()
        network.chain("A", "R", "B")
        val a = network.routers.getValue("A")
        val b = network.routers.getValue("B")
        val relay = network.routers.getValue("R")

        val frame = BlobChunkCodec.split(
            "note-2", ByteArray(1_000) { it.toByte() }, 2_000, "audio/mp4"
        ).single()
        val env = blobEnvelope("A", "B", frame)
        a.sendText("B", env)

        assertEquals(1, b.received().size)
        assertTrue("relay must not 'receive' a blob addressed elsewhere", relay.received().isEmpty())
        assertEquals(DeliveryState.DELIVERED, a.deliveryStateOf(env.messageId))
    }

    @Test
    fun `undecryptable blob chunk is not ACKed`() = runBlocking {
        // A node whose decryptor always fails must behave like the TEXT
        // path: no MessageReceived, no ACK, sender stays un-DELIVERED.
        val network = FakeNetwork()
        network.node("A")
        val broken = MeshRouter(
            localPeerId = "B",
            transport = object : MeshTransport {
                override fun connectedPeerIds(): Set<String> = setOf("A")
                override suspend fun sendTo(peerId: String, bytes: ByteArray): Boolean {
                    network.routers.getValue(peerId).onIncomingBytes("B", bytes)
                    return true
                }
                override fun broadcastExcept(excludePeerId: String?, bytes: ByteArray) = Unit
            },
            outbox = InMemoryOutboxStore(),
            decryptor = { null },
        )
        network.routers["B"] = broken
        network.adjacency.putIfAbsent("B", linkedSetOf("A"))
        network.connect("A", "B")
        val a = network.routers.getValue("A")

        val frame = BlobChunkCodec.split(
            "note-3", ByteArray(500) { 1 }, 1_000, "audio/mp4"
        ).single()
        val env = blobEnvelope("A", "B", frame)
        a.sendText("B", env)

        assertTrue(broken.received().isEmpty())
        assertTrue(a.deliveryStateOf(env.messageId) != DeliveryState.DELIVERED)
    }
}
