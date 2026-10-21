package com.p2pchat.messenger.mesh

/**
 * Simple per-peer sliding-window rate limiter for relay forwarding.
 *
 * A malicious or buggy peer must not be able to make this node flood the
 * mesh (amplification / battery drain). Each source peer may trigger at
 * most [maxPerWindow] forwards per [windowMs]; excess traffic is dropped,
 * not queued. Messages addressed TO this node are not rate-limited here —
 * this guards the *forwarding* path only.
 */
class RateLimiter(
    private val maxPerWindow: Int = MAX_FORWARDS_PER_MINUTE,
    private val windowMs: Long = WINDOW_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val hits = mutableMapOf<String, ArrayDeque<Long>>()

    /** @return true if [peerId] may trigger one more forward now. */
    @Synchronized
    fun tryForward(peerId: String): Boolean {
        val now = clock()
        val deque = hits.getOrPut(peerId) { ArrayDeque() }
        while (!deque.isEmpty() && now - deque.first() >= windowMs) {
            deque.removeFirst()
        }
        if (deque.size >= maxPerWindow) return false
        deque.addLast(now)
        return true
    }

    @Synchronized
    fun reset(peerId: String) {
        hits.remove(peerId)
    }

    companion object {
        const val MAX_FORWARDS_PER_MINUTE: Int = 30
        const val WINDOW_MS: Long = 60_000L
    }
}
