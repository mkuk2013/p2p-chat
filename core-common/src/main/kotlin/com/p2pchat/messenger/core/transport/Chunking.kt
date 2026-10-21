package com.p2pchat.messenger.core.transport

import java.nio.ByteBuffer

/**
 * BLE packet chunking. Wire header is 12 bytes:
 *  transferId (8, long) | index (2, unsigned short) | total (2, unsigned short)
 * followed by the chunk payload. MTU-aware callers choose maxPayload
 * (e.g. negotiatedMtu - 3 ATT overhead - 12 header).
 */
class PacketChunk(
    val transferId: Long,
    val index: Int,
    val total: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketChunk) return false
        return transferId == other.transferId && index == other.index &&
            total == other.total && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = transferId.hashCode()
        r = 31 * r + index
        r = 31 * r + total
        r = 31 * r + payload.contentHashCode()
        return r
    }

    override fun toString(): String =
        "PacketChunk(id=$transferId,$index/$total,${payload.size}B)"
}

object PacketChunker {
    const val HEADER_SIZE: Int = 12
    const val MAX_CHUNKS: Int = 65535

    fun split(data: ByteArray, maxPayload: Int, transferId: Long): List<PacketChunk> {
        require(maxPayload > 0) { "maxPayload must be > 0" }
        if (data.isEmpty()) {
            return listOf(PacketChunk(transferId, 0, 1, ByteArray(0)))
        }
        val total = (data.size + maxPayload - 1) / maxPayload
        require(total <= MAX_CHUNKS) { "Data too large to chunk: $total chunks" }
        return (0 until total).map { i ->
            val from = i * maxPayload
            val to = minOf(from + maxPayload, data.size)
            PacketChunk(transferId, i, total, data.copyOfRange(from, to))
        }
    }

    fun encode(chunk: PacketChunk): ByteArray {
        require(chunk.total in 1..MAX_CHUNKS) { "Invalid total" }
        require(chunk.index in 0 until chunk.total) { "Invalid index" }
        val buf = ByteBuffer.allocate(HEADER_SIZE + chunk.payload.size)
        buf.putLong(chunk.transferId)
        buf.putShort(chunk.index.toShort())
        buf.putShort(chunk.total.toShort())
        buf.put(chunk.payload)
        return buf.array()
    }

    fun decode(bytes: ByteArray): PacketChunk {
        require(bytes.size >= HEADER_SIZE) { "Chunk too short: ${bytes.size}" }
        val buf = ByteBuffer.wrap(bytes)
        val transferId = buf.long
        val index = buf.short.toInt() and 0xFFFF
        val total = buf.short.toInt() and 0xFFFF
        require(total > 0) { "Invalid total: $total" }
        require(index < total) { "Invalid index $index for total $total" }
        val payload = ByteArray(bytes.size - HEADER_SIZE)
        buf.get(payload)
        return PacketChunk(transferId, index, total, payload)
    }
}

/**
 * Reassembles chunks for possibly several concurrent transfers.
 * Thread-safe. Partial transfers older than [timeoutMs] are purged on
 * every [add] call. Duplicate chunks are ignored.
 */
class ChunkReassembler(private val timeoutMs: Long = 15_000L) {

    private class Partial(
        val total: Int,
        val createdAtMs: Long,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
    )

    private val partials = mutableMapOf<Long, Partial>()

    @Synchronized
    fun add(chunk: PacketChunk): ByteArray? = add(chunk, System.currentTimeMillis())

    @Synchronized
    fun add(chunk: PacketChunk, nowMs: Long): ByteArray? {
        purgeExpired(nowMs)
        require(chunk.total > 0 && chunk.index in 0 until chunk.total) { "Invalid chunk" }
        val partial = partials.getOrPut(chunk.transferId) { Partial(chunk.total, nowMs) }
        if (partial.total != chunk.total) return null // conflicting transfer, ignore
        if (partial.chunks.containsKey(chunk.index)) return null // duplicate
        partial.chunks[chunk.index] = chunk.payload.copyOf()
        if (partial.chunks.size < partial.total) return null
        // Complete — assemble in order and drop state.
        partials.remove(chunk.transferId)
        val totalSize = (0 until partial.total).sumOf { partial.chunks.getValue(it).size }
        val out = ByteArray(totalSize)
        var offset = 0
        for (i in 0 until partial.total) {
            val part = partial.chunks.getValue(i)
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    @Synchronized
    fun purgeExpired(nowMs: Long = System.currentTimeMillis()) {
        val it = partials.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value.createdAtMs > timeoutMs) it.remove()
        }
    }

    @Synchronized
    fun pendingTransferCount(): Int = partials.size

    @Synchronized
    fun clear() {
        partials.clear()
    }
}
