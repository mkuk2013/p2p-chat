package com.p2pchat.messenger.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTableTest {

    @Test
    fun `upsert and get round-trip`() {
        val table = PeerTable()
        table.upsert(PeerInfo(peerId = "peer1", displayName = "One", lastSeenMs = 100L))
        assertEquals("One", table.get("peer1")?.displayName)
        assertNull(table.get("missing"))
        assertEquals(1, table.size())
    }

    @Test
    fun `bare sighting does not erase known keys or name`() {
        val table = PeerTable()
        val xKey = byteArrayOf(1, 2, 3)
        val edKey = byteArrayOf(4, 5, 6)
        table.upsert(
            PeerInfo(
                peerId = "p", x25519Public = xKey, ed25519Public = edKey,
                displayName = "Known", lastSeenMs = 100L,
            ),
        )
        table.upsert(PeerInfo(peerId = "p", lastSeenMs = 200L))
        val merged = table.get("p")!!
        assertTrue(merged.x25519Public.contentEquals(xKey))
        assertTrue(merged.ed25519Public.contentEquals(edKey))
        assertEquals("Known", merged.displayName)
        assertEquals(200L, merged.lastSeenMs)
    }

    @Test
    fun `neighbours are ordered by hops then link quality`() {
        val table = PeerTable()
        table.upsert(PeerInfo(peerId = "far", hopsAway = 3, linkQuality = 1.0f, lastSeenMs = 10L))
        table.upsert(PeerInfo(peerId = "nearWeak", hopsAway = 1, linkQuality = 0.3f, lastSeenMs = 10L))
        table.upsert(PeerInfo(peerId = "nearStrong", hopsAway = 1, linkQuality = 0.9f, lastSeenMs = 10L))
        assertEquals(
            listOf("nearStrong", "nearWeak", "far"),
            table.neighbours().map { it.peerId },
        )
        assertEquals(
            listOf("nearStrong", "nearWeak"),
            table.directNeighbours().map { it.peerId },
        )
    }

    @Test
    fun `pruneStale removes only entries older than 90 seconds`() {
        val table = PeerTable()
        val now = 1_000_000L
        table.upsert(PeerInfo(peerId = "fresh", lastSeenMs = now - 10_000L))
        table.upsert(PeerInfo(peerId = "stale", lastSeenMs = now - 91_000L))
        table.upsert(PeerInfo(peerId = "edge", lastSeenMs = now - 90_000L))
        val removed = table.pruneStale(nowMs = now)
        assertEquals(1, removed)
        assertEquals(setOf("fresh", "edge"), table.all().map { it.peerId }.toSet())
    }

    @Test
    fun `markSeen creates and refreshes entries`() {
        val table = PeerTable()
        table.markSeen("p", nowMs = 100L, address = "AA:BB")
        assertEquals("AA:BB", table.get("p")?.address)
        table.markSeen("p", nowMs = 250L)
        assertEquals(250L, table.get("p")?.lastSeenMs)
        // Older timestamps never move lastSeen backwards.
        table.markSeen("p", nowMs = 50L)
        assertEquals(250L, table.get("p")?.lastSeenMs)
    }
}
