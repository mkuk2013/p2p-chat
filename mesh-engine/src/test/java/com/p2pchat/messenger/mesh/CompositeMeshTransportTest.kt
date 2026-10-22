package com.p2pchat.messenger.mesh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeMeshTransportTest {

    private class FakeTransport(
        val peers: Set<String>,
        val failPeers: Set<String> = emptySet(),
    ) : MeshTransport {
        val sent = mutableListOf<Pair<String, ByteArray>>()
        val broadcasts = mutableListOf<Pair<String?, ByteArray>>()

        override fun connectedPeerIds(): Set<String> = peers

        override suspend fun sendTo(peerId: String, bytes: ByteArray): Boolean {
            if (peerId in failPeers) return false
            sent += peerId to bytes
            return true
        }

        override fun broadcastExcept(excludePeerId: String?, bytes: ByteArray) {
            broadcasts += excludePeerId to bytes
        }
    }

    private val payload = byteArrayOf(1, 2, 3)

    @Test
    fun `connected peers are the union of all transports`() {
        val composite = CompositeMeshTransport(
            listOf(FakeTransport(setOf("a", "b")), FakeTransport(setOf("b", "c"))),
        )
        assertEquals(setOf("a", "b", "c"), composite.connectedPeerIds())
    }

    @Test
    fun `send goes to the first transport that has the peer and succeeds`() = runBlocking {
        val first = FakeTransport(setOf("a"))
        val second = FakeTransport(setOf("a", "b"))
        val composite = CompositeMeshTransport(listOf(first, second))
        assertTrue(composite.sendTo("a", payload))
        assertEquals(1, first.sent.size)
        assertEquals(0, second.sent.size)
        assertTrue(composite.sendTo("b", payload))
        assertEquals(1, second.sent.size)
    }

    @Test
    fun `send falls through when the first transport fails`() = runBlocking {
        val first = FakeTransport(setOf("a"), failPeers = setOf("a"))
        val second = FakeTransport(setOf("a"))
        val composite = CompositeMeshTransport(listOf(first, second))
        assertTrue(composite.sendTo("a", payload))
        assertEquals(0, first.sent.size)
        assertEquals(1, second.sent.size)
    }

    @Test
    fun `send to an unknown peer fails`() = runBlocking {
        val composite = CompositeMeshTransport(listOf(FakeTransport(setOf("a"))))
        assertFalse(composite.sendTo("zzz", payload))
    }

    @Test
    fun `broadcast fans out to every transport`() {
        val first = FakeTransport(setOf("a"))
        val second = FakeTransport(setOf("b"))
        val composite = CompositeMeshTransport(listOf(first, second))
        composite.broadcastExcept("a", payload)
        assertEquals(1, first.broadcasts.size)
        assertEquals(1, second.broadcasts.size)
        assertEquals("a", first.broadcasts.single().first)
    }
}
