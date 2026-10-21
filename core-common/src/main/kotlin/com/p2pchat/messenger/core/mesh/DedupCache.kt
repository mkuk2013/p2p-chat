package com.p2pchat.messenger.core.mesh

/**
 * Thread-safe LRU + TTL deduplication cache for message IDs.
 * Prevents relay loops and duplicate delivery. When the cache exceeds
 * [maxSize], the least-recently-seen entry is evicted. Entries older
 * than [ttlMs] are treated as unseen.
 */
class SeenMessageCache(
    private val maxSize: Int = 2000,
    private val ttlMs: Long = 24L * 60 * 60 * 1000,
) {
    init {
        require(maxSize > 0) { "maxSize must be > 0" }
        require(ttlMs > 0) { "ttlMs must be > 0" }
    }

    // accessOrder = true gives LRU ordering.
    private val entries = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > maxSize
    }

    /** Returns true if [id] is NEW (first time seen), false if duplicate. */
    @Synchronized
    fun markSeen(id: String): Boolean = markSeen(id, System.currentTimeMillis())

    @Synchronized
    fun markSeen(id: String, nowMs: Long): Boolean {
        prune(nowMs)
        val existing = entries[id] // get() also refreshes LRU order
        if (existing != null && nowMs - existing <= ttlMs) return false
        entries[id] = nowMs
        return true
    }

    @Synchronized
    fun hasSeen(id: String): Boolean = hasSeen(id, System.currentTimeMillis())

    @Synchronized
    fun hasSeen(id: String, nowMs: Long): Boolean {
        val ts = entries[id] ?: return false
        if (nowMs - ts > ttlMs) {
            entries.remove(id)
            return false
        }
        return true
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun prune() {
        prune(System.currentTimeMillis())
    }

    @Synchronized
    fun prune(nowMs: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value > ttlMs) it.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
