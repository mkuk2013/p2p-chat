package com.p2pchat.messenger.core.transport

/**
 * Frames + maths for the Bluetooth connection-speed check ("capability
 * test", master spec §7).
 *
 * The check runs over the app's real BLE data channel between two
 * phones that are already linked: the initiator sends PING frames and
 * the peer's transport answers PONG immediately; then the initiator
 * pushes a burst of DATA frames as fast as the channel accepts them.
 * Everything reported to the user is measured from those real
 * exchanges — nothing is simulated.
 *
 * Frame layout (before the transport's own chunking):
 * `P2PT` magic (4 bytes) | type (1 byte) | sequence (4 bytes, BE) |
 * filler up to the requested total size. The magic cannot collide
 * with mesh envelopes (codec magic "P2P1") or identity announcements
 * (leading '{'), and the transport demuxes these frames before the
 * mesh layer ever sees them.
 */
object LinkTestFrames {

    const val TYPE_PING: Byte = 1
    const val TYPE_PONG: Byte = 2
    const val TYPE_DATA: Byte = 3

    const val HEADER_BYTES: Int = 9

    private val MAGIC = byteArrayOf('P'.code.toByte(), '2'.code.toByte(), 'P'.code.toByte(), 'T'.code.toByte())

    data class Frame(val type: Byte, val seq: Int, val size: Int)

    /** Build a frame of exactly [totalSize] bytes (at least the header). */
    fun encode(type: Byte, seq: Int, totalSize: Int): ByteArray {
        val size = maxOf(totalSize, HEADER_BYTES)
        val out = ByteArray(size)
        MAGIC.copyInto(out, 0)
        out[4] = type
        out[5] = (seq ushr 24).toByte()
        out[6] = (seq ushr 16).toByte()
        out[7] = (seq ushr 8).toByte()
        out[8] = seq.toByte()
        // Deterministic filler so a frame is recognisable in a hex dump.
        for (i in HEADER_BYTES until size) out[i] = (i * 31 + seq).toByte()
        return out
    }

    /** The PONG answering [ping]: same sequence and size, type flipped. */
    fun pongOf(ping: ByteArray): ByteArray? {
        val frame = parse(ping) ?: return null
        if (frame.type != TYPE_PING) return null
        return encode(TYPE_PONG, frame.seq, frame.size)
    }

    fun parse(bytes: ByteArray): Frame? {
        if (!isTestFrame(bytes)) return null
        val seq = ((bytes[5].toInt() and 0xFF) shl 24) or
            ((bytes[6].toInt() and 0xFF) shl 16) or
            ((bytes[7].toInt() and 0xFF) shl 8) or
            (bytes[8].toInt() and 0xFF)
        return Frame(type = bytes[4], seq = seq, size = bytes.size)
    }

    fun isTestFrame(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_BYTES) return false
        return bytes[0] == MAGIC[0] && bytes[1] == MAGIC[1] &&
            bytes[2] == MAGIC[2] && bytes[3] == MAGIC[3] &&
            (bytes[4] == TYPE_PING || bytes[4] == TYPE_PONG || bytes[4] == TYPE_DATA)
    }
}

/** One completed connection-speed measurement over a real link. */
data class LinkTestResult(
    val address: String,
    /** Payload bytes accepted by the channel during the burst. */
    val totalBytes: Long,
    /** Wall time of the burst, milliseconds. */
    val durationMs: Long,
    /** Round-trip times of the ping/pong samples, milliseconds. */
    val rttSamplesMs: List<Long>,
) {
    val throughputKBps: Double get() = LinkTestMath.throughputKBps(totalBytes, durationMs)
    val averageRttMs: Double get() = LinkTestMath.averageRttMs(rttSamplesMs)
    val minRttMs: Long get() = LinkTestMath.minRttMs(rttSamplesMs)
    val verdict: LinkTestMath.VoiceVerdict get() =
        LinkTestMath.voiceVerdict(throughputKBps, averageRttMs)
}

/** Pure arithmetic behind the speed check — unit-tested on the JVM. */
object LinkTestMath {

    enum class VoiceVerdict {
        /** Fast + steady enough that live voice could work on this link. */
        VOICE_OK,

        /** Throughput below what the app's voice format needs. */
        TOO_SLOW,

        /** Fast enough on average, but answers come back too slowly
         *  for a conversation (long pauses between words). */
        UNSTEADY,
    }

    /** The app's voice format is PCM 16 kHz mono 16-bit = 32 KB/s.
     *  A link must beat that with headroom before we say voice could
     *  work on it; video needs orders of magnitude more and is never
     *  claimed for Bluetooth. */
    const val VOICE_MIN_THROUGHPUT_KBPS: Double = 36.0
    const val VOICE_MAX_AVG_RTT_MS: Double = 400.0

    fun throughputKBps(totalBytes: Long, durationMs: Long): Double {
        if (totalBytes <= 0 || durationMs <= 0) return 0.0
        return totalBytes * 1000.0 / durationMs / 1024.0
    }

    fun averageRttMs(samplesMs: List<Long>): Double =
        if (samplesMs.isEmpty()) 0.0 else samplesMs.average()

    fun minRttMs(samplesMs: List<Long>): Long = samplesMs.minOrNull() ?: 0L

    fun voiceVerdict(throughputKBps: Double, averageRttMs: Double): VoiceVerdict = when {
        throughputKBps < VOICE_MIN_THROUGHPUT_KBPS -> VoiceVerdict.TOO_SLOW
        averageRttMs > VOICE_MAX_AVG_RTT_MS -> VoiceVerdict.UNSTEADY
        else -> VoiceVerdict.VOICE_OK
    }
}
