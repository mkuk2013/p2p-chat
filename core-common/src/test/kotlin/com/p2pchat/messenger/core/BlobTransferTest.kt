package com.p2pchat.messenger.core

import com.p2pchat.messenger.core.blob.BlobChunk
import com.p2pchat.messenger.core.blob.BlobChunkCodec
import com.p2pchat.messenger.core.blob.BlobReassembler
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Blob (voice note) chunking: split → frames → reassemble, covering the
 * hostile realities of a multi-hop flood — out-of-order arrival,
 * duplicates, missing chunks, corrupted payloads and conflicting frames.
 */
class BlobTransferTest {

    private fun sampleBytes(size: Int, seed: Long = 42): ByteArray =
        ByteArray(size).also { Random(seed).nextBytes(it) }

    private fun splitToChunks(blobId: String, data: ByteArray, durationMs: Int = 7_000): List<BlobChunk> =
        BlobChunkCodec.split(blobId, data, durationMs, "audio/mp4", chunkPayloadBytes = 1024)
            .map { BlobChunkCodec.decode(it) }

    @Test
    fun `split and reassemble round trips exactly`() {
        val data = sampleBytes(10_000)
        val chunks = splitToChunks("blob-1", data)
        assertEquals(10, chunks.size) // ceil(10000 / 1024)
        val reassembler = BlobReassembler()
        chunks.forEach { reassembler.add(it) }
        assertTrue(reassembler.isComplete())
        assertArrayEquals(data, reassembler.assemble())
    }

    @Test
    fun `single chunk blob round trips`() {
        val data = sampleBytes(300)
        val chunks = splitToChunks("blob-small", data)
        assertEquals(1, chunks.size)
        val reassembler = BlobReassembler()
        assertEquals(BlobReassembler.AddResult.COMPLETED, reassembler.add(chunks.single()))
        assertArrayEquals(data, reassembler.assemble())
    }

    @Test
    fun `out of order arrival still assembles`() {
        val data = sampleBytes(5_500)
        val chunks = splitToChunks("blob-ooo", data).shuffled(Random(7))
        val reassembler = BlobReassembler()
        chunks.forEach { reassembler.add(it) }
        assertArrayEquals(data, reassembler.assemble())
    }

    @Test
    fun `duplicate chunks are ignored and do not corrupt`() {
        val data = sampleBytes(4_096)
        val chunks = splitToChunks("blob-dup", data)
        val reassembler = BlobReassembler()
        assertEquals(BlobReassembler.AddResult.ACCEPTED, reassembler.add(chunks[0]))
        assertEquals(BlobReassembler.AddResult.DUPLICATE, reassembler.add(chunks[0]))
        assertEquals(1, reassembler.receivedCount)
        chunks.drop(1).forEach { reassembler.add(it) }
        assertArrayEquals(data, reassembler.assemble())
    }

    @Test
    fun `incomplete blob refuses to assemble`() {
        val data = sampleBytes(4_096)
        val chunks = splitToChunks("blob-partial", data)
        val reassembler = BlobReassembler()
        chunks.dropLast(1).forEach { reassembler.add(it) }
        assertEquals(listOf(chunks.lastIndex), reassembler.missingIndices())
        assertNull("incomplete blob must not assemble", reassembler.assemble())
    }

    @Test
    fun `corrupted chunk fails the integrity check`() {
        val data = sampleBytes(4_096)
        val chunks = splitToChunks("blob-corrupt", data).toMutableList()
        val victim = chunks[2]
        val tamperedPayload = victim.payload.copyOf().also { it[0] = (it[0] + 1).toByte() }
        chunks[2] = BlobChunk(
            victim.blobId, victim.index, victim.total, victim.totalBytes,
            victim.sha256, victim.durationMs, victim.mime, tamperedPayload
        )
        val reassembler = BlobReassembler()
        chunks.forEach { reassembler.add(it) }
        assertTrue(reassembler.isComplete())
        assertNull("hash mismatch must reject the blob", reassembler.assemble())
    }

    @Test
    fun `conflicting frame metadata is rejected without corrupting state`() {
        val data = sampleBytes(4_096)
        val chunks = splitToChunks("blob-a", data)
        val reassembler = BlobReassembler()
        reassembler.add(chunks[0])
        // Same blobId but a different announced total: conflict.
        val alien = BlobChunk(
            blobId = "blob-a", index = 1, total = 99, totalBytes = data.size.toLong(),
            sha256 = BlobChunkCodec.sha256(data), durationMs = 7_000,
            mime = "audio/mp4", payload = ByteArray(16)
        )
        assertEquals(BlobReassembler.AddResult.REJECTED_CONFLICT, reassembler.add(alien))
        assertEquals(1, reassembler.receivedCount)
        // A frame for a DIFFERENT blob must not join this reassembler either.
        val other = splitToChunks("blob-b", data)[1]
        assertEquals(BlobReassembler.AddResult.REJECTED_CONFLICT, reassembler.add(other))
        // Original blob still completes cleanly afterwards.
        chunks.drop(1).forEach { reassembler.add(it) }
        assertArrayEquals(data, reassembler.assemble())
    }

    @Test
    fun `frame codec round trips and rejects garbage`() {
        val data = sampleBytes(2_048)
        val frame = BlobChunkCodec.split("blob-x", data, 3_500, "audio/mp4", chunkPayloadBytes = 2048).single()
        val chunk = BlobChunkCodec.decode(frame)
        assertEquals("blob-x", chunk.blobId)
        assertEquals(0, chunk.index)
        assertEquals(1, chunk.total)
        assertEquals(2_048L, chunk.totalBytes)
        assertEquals(3_500, chunk.durationMs)
        assertArrayEquals(data, chunk.payload)
        try {
            BlobChunkCodec.decode(byteArrayOf(1, 2, 3))
            fail("short garbage must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        val badMagic = frame.copyOf().also { it[0] = 'X'.code.toByte() }
        try {
            BlobChunkCodec.decode(badMagic)
            fail("bad magic must be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `named BLB2 frames round trip with the file name intact`() {
        val data = sampleBytes(20_000)
        val frames = BlobChunkCodec.split(
            "blob-file", data, 0, "application/pdf", name = "report card.pdf"
        )
        assertTrue(frames.size > 1)
        val chunks = frames.map { BlobChunkCodec.decode(it) }
        for (chunk in chunks) {
            assertEquals("report card.pdf", chunk.name)
            assertEquals("application/pdf", chunk.mime)
        }
        val reassembler = BlobReassembler()
        for (chunk in chunks) reassembler.add(chunk)
        assertEquals("report card.pdf", reassembler.mediaName)
        assertEquals("application/pdf", reassembler.mediaMime)
        assertNotNull(reassembler.assemble())
        assertArrayEquals(data, reassembler.assemble())
        // Named frames carry the BLB2 magic; unnamed keep BLB1.
        assertEquals('2'.code.toByte(), frames[0][3])
        val voiceFrames = BlobChunkCodec.split("blob-voice", data, 5_000, "audio/mp4")
        assertEquals('1'.code.toByte(), voiceFrames[0][3])
        assertEquals("", BlobChunkCodec.decode(voiceFrames[0]).name)
    }

    @Test
    fun `oversize and empty blobs are refused at split`() {
        try {
            BlobChunkCodec.split("blob-big", ByteArray(BlobChunkCodec.MAX_BLOB_BYTES + 1), 0, "audio/mp4")
            fail("oversize blob must be refused")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        try {
            BlobChunkCodec.split("blob-empty", ByteArray(0), 0, "audio/mp4")
            fail("empty blob must be refused")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        assertNotNull(
            BlobChunkCodec.split("blob-max", ByteArray(BlobChunkCodec.MAX_BLOB_BYTES), 0, "audio/mp4")
        )
    }
}
