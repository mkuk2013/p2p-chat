package com.p2pchat.messenger.crypto

import kotlin.math.abs

/** True if [timestampMs] is within [windowMs] of [nowMs] (either direction). */
fun isTimestampFresh(
    timestampMs: Long,
    nowMs: Long = System.currentTimeMillis(),
    windowMs: Long = DEFAULT_FRESHNESS_WINDOW_MS,
): Boolean = abs(nowMs - timestampMs) <= windowMs

const val DEFAULT_FRESHNESS_WINDOW_MS: Long = 5L * 60 * 1000 // 5 minutes

/**
 * Replay guard combining timestamp freshness with a bounded set of seen
 * nonces / message IDs. Thread-safe. Entries are pruned once they fall
 * outside the freshness window.
 */
class NonceWindow(
    private val windowMs: Long = DEFAULT_FRESHNESS_WINDOW_MS,
    private val maxEntries: Int = 4096,
) {
    private val seen = LinkedHashMap<String, Long>()

    @Synchronized
    fun isFresh(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        isTimestampFresh(timestampMs, nowMs, windowMs)

    /**
     * Returns true and records [nonceKey] if it is fresh and unseen;
     * false if it is stale or a replay.
     */
    @Synchronized
    fun checkAndMark(
        nonceKey: String,
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isTimestampFresh(timestampMs, nowMs, windowMs)) return false
        prune(nowMs)
        if (seen.containsKey(nonceKey)) return false
        seen[nonceKey] = timestampMs
        while (seen.size > maxEntries) {
            val eldest = seen.keys.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            } else break
        }
        return true
    }

    @Synchronized
    fun prune(nowMs: Long = System.currentTimeMillis()) {
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            if (abs(nowMs - it.next().value) > windowMs) it.remove()
        }
    }

    @Synchronized
    fun size(): Int = seen.size
}
