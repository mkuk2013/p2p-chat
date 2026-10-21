package com.p2pchat.messenger.core

import com.p2pchat.messenger.core.transport.ChunkReassembler
import com.p2pchat.messenger.core.transport.PacketChunker
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkingTest {

    @Test
    fun splitAndReassembleInOrder() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        val chunks = PacketChunker.split(data, maxPayload = 100, transferId = 42L)
        assertEquals(10, chunks.size)
        val reassembler = ChunkReassembler()
        var result: ByteArray? = null
        for (chunk in chunks) result = reassembler.add(chunk) ?: result
        assertArrayEquals(data, result)
    }

    @Test
    fun reassembleOutOfOrder() {
        val data = "multi-hop mesh payload".repeat(20).toByteArray()
        val chunks = PacketChunker.split(data, maxPayload = 17, transferId = 7L)
        val reassembler = ChunkReassembler()
        var result: ByteArray? = null
        for (chunk in chunks.shuffled(java.util.Random(1))) {
            reassembler.add(chunk)?.let { result = it }
        }
        assertArrayEquals(data, result)
    }

    @Test
    fun encodeDecodeRoundtrip() {
        val data = ByteArray(300) { it.toByte() }
        val chunks = PacketChunker.split(data, maxPayload = 64, transferId = 123456789L)
        for (chunk in chunks) {
            val decoded = PacketChunker.decode(PacketChunker.encode(chunk))
            assertEquals(chunk, decoded)
            val wire = PacketChunker.encode(chunk)
            assertEquals(PacketChunker.HEADER_SIZE + chunk.payload.size, wire.size)
        }
    }

    @Test
    fun duplicateChunkIgnored() {
        val data = ByteArray(50) { it.toByte() }
        val chunks = PacketChunker.split(data, maxPayload = 20, transferId = 9L)
        val reassembler = ChunkReassembler()
        assertNull(reassembler.add(chunks[0]))
        assertNull(reassembler.add(chunks[0])) // duplicate
        assertEquals(1, reassembler.pendingTransferCount())
        assertNull(reassembler.add(chunks[1]))
        val done = reassembler.add(chunks[2])
        assertArrayEquals(data, done)
        assertEquals(0, reassembler.pendingTransferCount())
    }

    @Test
    fun expiredPartialIsPurged() {
        val data = ByteArray(100) { it.toByte() }
        val chunks = PacketChunker.split(data, maxPayload = 10, transferId = 5L)
        val reassembler = ChunkReassembler(timeoutMs = 1_000L)
        assertNull(reassembler.add(chunks[0], nowMs = 0L))
        assertEquals(1, reassembler.pendingTransferCount())
        // A chunk for another transfer far in the future triggers purge.
        val other = PacketChunker.split(data, maxPayload = 10, transferId = 6L)
        assertNull(reassembler.add(other[0], nowMs = 10_000L))
        assertEquals(1, reassembler.pendingTransferCount()) // only transfer 6 remains
        // Late chunks of the expired transfer start a fresh partial.
        assertNull(reassembler.add(chunks[1], nowMs = 10_001L))
        assertEquals(2, reassembler.pendingTransferCount())
    }

    @Test
    fun emptyDataProducesSingleChunk() {
        val chunks = PacketChunker.split(ByteArray(0), maxPayload = 20, transferId = 1L)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].payload.isEmpty())
        val reassembler = ChunkReassembler()
        val result = reassembler.add(chunks[0])
        assertArrayEquals(ByteArray(0), result)
    }

    @Test
    fun exactMultipleHasNoTrailingEmptyChunk() {
        val chunks = PacketChunker.split(ByteArray(200), maxPayload = 100, transferId = 3L)
        assertEquals(2, chunks.size)
    }
}
