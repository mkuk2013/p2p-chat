package com.p2pchat.messenger.core

import com.p2pchat.messenger.core.mesh.SeenMessageCache
import com.p2pchat.messenger.core.model.PeerIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupCacheTest {

    @Test
    fun newVsDuplicate() {
        val cache = SeenMessageCache(maxSize = 100, ttlMs = 60_000L)
        assertTrue(cache.markSeen("m1", nowMs = 0L))
        assertFalse(cache.markSeen("m1", nowMs = 1_000L))
        assertTrue(cache.hasSeen("m1", nowMs = 1_000L))
        assertFalse(cache.hasSeen("unknown", nowMs = 1_000L))
        assertEquals(1, cache.size())
    }

    @Test
    fun lruEviction() {
        val cache = SeenMessageCache(maxSize = 3, ttlMs = 60_000L)
        assertTrue(cache.markSeen("a", 0L))
        assertTrue(cache.markSeen("b", 1L))
        assertTrue(cache.markSeen("c", 2L))
        assertEquals(3, cache.size())
        assertTrue(cache.markSeen("d", 3L)) // evicts "a" (eldest)
        assertEquals(3, cache.size())
        assertFalse(cache.hasSeen("a", 4L))
        assertTrue(cache.hasSeen("d", 4L))
        // "a" counts as new again after eviction
        assertTrue(cache.markSeen("a", 5L))
    }

    @Test
    fun ttlPrune() {
        val cache = SeenMessageCache(maxSize = 100, ttlMs = 1_000L)
        assertTrue(cache.markSeen("x", nowMs = 0L))
        assertTrue(cache.hasSeen("x", nowMs = 500L))
        assertFalse(cache.hasSeen("x", nowMs = 2_000L)) // expired on read
        assertTrue(cache.markSeen("y", nowMs = 0L))
        cache.prune(nowMs = 5_000L)
        assertFalse(cache.hasSeen("y", nowMs = 5_000L))
        // Expired entries are new again
        assertTrue(cache.markSeen("y", nowMs = 5_000L))
    }

    @Test
    fun peerIdDerivationAndValidation() {
        val pub = ByteArray(32) { (it * 7).toByte() }
        val id1 = PeerIds.fromPublicKey(pub)
        val id2 = PeerIds.fromPublicKey(pub.copyOf())
        assertEquals(id1, id2)
        assertEquals(32, id1.length)
        assertTrue(PeerIds.isValid(id1))
        assertFalse(PeerIds.isValid(id1.uppercase()))
        assertFalse(PeerIds.isValid("xyz"))
        assertFalse(PeerIds.isValid(""))
        val other = PeerIds.fromPublicKey(ByteArray(32) { (it * 7 + 1).toByte() })
        assertFalse(id1 == other)
    }
}
