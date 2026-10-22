package com.p2pchat.messenger.core

import com.p2pchat.messenger.core.transport.LinkTestFrames
import com.p2pchat.messenger.core.transport.LinkTestMath
import com.p2pchat.messenger.core.transport.LinkTestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The connection-speed check: frame codec, throughput/latency maths
 * and the voice verdict thresholds. All values are computed from real
 * measurements at runtime — these tests only pin the arithmetic.
 */
class LinkTestTest {

    @Test
    fun `frame round-trips type sequence and size`() {
        val bytes = LinkTestFrames.encode(LinkTestFrames.TYPE_PING, 42, 100)
        assertEquals(100, bytes.size)
        val frame = LinkTestFrames.parse(bytes)
        assertNotNull(frame)
        assertEquals(LinkTestFrames.TYPE_PING, frame!!.type)
        assertEquals(42, frame.seq)
        assertEquals(100, frame.size)
    }

    @Test
    fun `small frames are padded to the header size`() {
        val bytes = LinkTestFrames.encode(LinkTestFrames.TYPE_DATA, 1, 2)
        assertEquals(LinkTestFrames.HEADER_BYTES, bytes.size)
        assertNotNull(LinkTestFrames.parse(bytes))
    }

    @Test
    fun `mesh envelopes and identity json are not test frames`() {
        val mesh = "P2P1-envelope-bytes".toByteArray(Charsets.UTF_8)
        assertFalse(LinkTestFrames.isTestFrame(mesh))
        assertNull(LinkTestFrames.parse(mesh))
        val json = "{\"peerId\":\"abc\"}".toByteArray(Charsets.UTF_8)
        assertFalse(LinkTestFrames.isTestFrame(json))
        assertFalse(LinkTestFrames.isTestFrame(ByteArray(4)))
    }

    @Test
    fun `pong answers a ping with the same sequence and size`() {
        val ping = LinkTestFrames.encode(LinkTestFrames.TYPE_PING, 7, 64)
        val pong = LinkTestFrames.pongOf(ping)
        assertNotNull(pong)
        val frame = LinkTestFrames.parse(pong!!)
        assertEquals(LinkTestFrames.TYPE_PONG, frame!!.type)
        assertEquals(7, frame.seq)
        assertEquals(64, frame.size)
        // A DATA frame has no pong.
        val data = LinkTestFrames.encode(LinkTestFrames.TYPE_DATA, 7, 64)
        assertNull(LinkTestFrames.pongOf(data))
    }

    @Test
    fun `throughput is bytes over time in KB per second`() {
        // 20 KB in 1 s = 20 KB/s; 20 KB in 500 ms = 40 KB/s.
        assertEquals(20.0, LinkTestMath.throughputKBps(20L * 1024, 1000), 0.001)
        assertEquals(40.0, LinkTestMath.throughputKBps(20L * 1024, 500), 0.001)
        assertEquals(0.0, LinkTestMath.throughputKBps(0, 1000), 0.0)
        assertEquals(0.0, LinkTestMath.throughputKBps(1024, 0), 0.0)
    }

    @Test
    fun `rtt average and minimum`() {
        assertEquals(200.0, LinkTestMath.averageRttMs(listOf(100L, 200L, 300L)), 0.001)
        assertEquals(100L, LinkTestMath.minRttMs(listOf(100L, 200L, 300L)))
        assertEquals(0.0, LinkTestMath.averageRttMs(emptyList()), 0.0)
        assertEquals(0L, LinkTestMath.minRttMs(emptyList()))
    }

    @Test
    fun `verdict needs both speed and steady answers for voice`() {
        assertEquals(
            LinkTestMath.VoiceVerdict.VOICE_OK,
            LinkTestMath.voiceVerdict(64.0, 120.0),
        )
        // The app's voice format needs 32 KB/s; 20 KB/s cannot carry it.
        assertEquals(
            LinkTestMath.VoiceVerdict.TOO_SLOW,
            LinkTestMath.voiceVerdict(20.0, 100.0),
        )
        // Fast but sluggish answers would chop a conversation up.
        assertEquals(
            LinkTestMath.VoiceVerdict.UNSTEADY,
            LinkTestMath.voiceVerdict(80.0, 900.0),
        )
    }

    @Test
    fun `result derives its numbers through the same maths`() {
        val result = LinkTestResult(
            address = "AA:BB:CC:DD:EE:FF",
            totalBytes = 40L * 1024,
            durationMs = 1000,
            rttSamplesMs = listOf(50L, 70L),
        )
        assertEquals(40.0, result.throughputKBps, 0.001)
        assertEquals(60.0, result.averageRttMs, 0.001)
        assertEquals(50L, result.minRttMs)
        assertEquals(LinkTestMath.VoiceVerdict.VOICE_OK, result.verdict)
        assertTrue(LinkTestMath.VOICE_MIN_THROUGHPUT_KBPS > 32.0)
    }
}
