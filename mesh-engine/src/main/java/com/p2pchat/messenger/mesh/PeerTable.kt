package com.p2pchat.messenger.mesh

import java.util.concurrent.ConcurrentHashMap

/**
 * A peer this node knows about.
 *
 * [hopsAway] is 1 for direct Bluetooth neighbours; larger values come from
 * routing gossip and are informational only — the flood router in this
 * version does not require an exact distance to forward correctly.
 * [linkQuality] is a 0.0–1.0 heuristic (recent success ratio / RSSI based,
 * maintained by the app layer); it is only used to order neighbours.
 */
data class PeerInfo(
    val peerId: String,
    val address: String? = null,
    val x25519Public: ByteArray? = null,
    val ed25519Public: ByteArray? = null,
    val displayName: String? = null,
    val lastSeenMs: Long = 0L,
    val hopsAway: Int = 1,
    val linkQuality: Float = 1.0f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerInfo) return false
        return peerId == other.peerId &&
            address == other.address &&
            x25519Public.contentEquals(other.x25519Public) &&
            ed25519Public.contentEquals(other.ed25519Public) &&
            displayName == other.displayName &&
            lastSeenMs == other.lastSeenMs &&
            hopsAway == other.hopsAway &&
            linkQuality == other.linkQuality
    }

    override fun hashCode(): Int {
        var r = peerId.hashCode()
        r = 31 * r + (address?.hashCode() ?: 0)
        r = 31 * r + x25519Public.contentHashCode()
        r = 31 * r + ed25519Public.contentHashCode()
        r = 31 * r + (displayName?.hashCode() ?: 0)
        r = 31 * r + lastSeenMs.hashCode()
        r = 31 * r + hopsAway
        r = 31 * r + linkQuality.hashCode()
        return r
    }
}

/**
 * Thread-safe table of known peers. Entries go stale: anything not seen for
 * [STALE_AFTER_MS] (90 s) is removed by [pruneStale] and must not be treated
 * as a live neighbour.
 */
class PeerTable {

    private val peers = ConcurrentHashMap<String, PeerInfo>()

    /** Insert or replace the entry for [PeerInfo.peerId]. When [info] has a
     * null/empty field that the existing entry knows (keys, name, address),
     * the known value is kept — a bare "seen" sighting must not erase keys. */
    fun upsert(info: PeerInfo) {
        peers.merge(info.peerId, info) { old, new ->
            PeerInfo(
                peerId = new.peerId,
                address = new.address ?: old.address,
                x25519Public = new.x25519Public ?: old.x25519Public,
                ed25519Public = new.ed25519Public ?: old.ed25519Public,
                displayName = new.displayName ?: old.displayName,
                lastSeenMs = maxOf(old.lastSeenMs, new.lastSeenMs),
                hopsAway = minOf(old.hopsAway, new.hopsAway),
                linkQuality = new.linkQuality,
            )
        }
    }

    /** Record a sighting of [peerId] at [nowMs] (creates a bare entry if new). */
    fun markSeen(peerId: String, nowMs: Long = System.currentTimeMillis(), address: String? = null) {
        val existing = peers[peerId]
        if (existing == null) {
            peers[peerId] = PeerInfo(peerId = peerId, address = address, lastSeenMs = nowMs)
        } else {
            peers[peerId] = existing.copy(
                lastSeenMs = maxOf(existing.lastSeenMs, nowMs),
                address = address ?: existing.address,
            )
        }
    }

    fun get(peerId: String): PeerInfo? = peers[peerId]

    fun remove(peerId: String) {
        peers.remove(peerId)
    }

    fun all(): List<PeerInfo> = peers.values.sortedBy { it.peerId }

    fun size(): Int = peers.size

    fun clear() = peers.clear()

    /**
     * All known peers that are plausibly reachable, best first:
     * fewest hops away, then highest link quality, then most recently seen.
     */
    fun neighbours(): List<PeerInfo> =
        peers.values.sortedWith(
            compareBy<PeerInfo> { it.hopsAway }
                .thenByDescending { it.linkQuality }
                .thenByDescending { it.lastSeenMs },
        )

    /** Direct (1-hop) neighbours only — the peers a flood can actually reach now. */
    fun directNeighbours(): List<PeerInfo> = neighbours().filter { it.hopsAway <= 1 }

    /**
     * Remove entries not seen within [staleAfterMs] of [nowMs].
     * @return how many entries were removed.
     */
    fun pruneStale(
        nowMs: Long = System.currentTimeMillis(),
        staleAfterMs: Long = STALE_AFTER_MS,
    ): Int {
        var removed = 0
        val iterator = peers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastSeenMs > staleAfterMs) {
                iterator.remove()
                removed++
            }
        }
        return removed
    }

    companion object {
        const val STALE_AFTER_MS: Long = 90_000L
    }
}
