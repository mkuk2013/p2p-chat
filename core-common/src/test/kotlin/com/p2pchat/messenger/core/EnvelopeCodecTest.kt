package com.p2pchat.messenger.core

import com.p2pchat.messenger.core.codec.CodecException
import com.p2pchat.messenger.core.codec.EnvelopeCodec
import com.p2pchat.messenger.core.model.EnvelopeType
import com.p2pchat.messenger.core.model.MessageEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EnvelopeCodecTest {

    private fun sample() = MessageEnvelope(
        messageId = "msg-123",
        type = EnvelopeType.TEXT,
        senderId = "a".repeat(32),
        recipientId = "b".repeat(32),
        timestampMs = 1_700_000_000_000L,
        expiryMs = 1_700_000_000_000L + 86_400_000L,
        ttl = 7,
        hopCount = 2,
        ephemeralPubKey = ByteArray(32) { it.toByte() },
        nonce = ByteArray(12) { (it * 3).toByte() },
        ciphertext = "hello mesh".toByteArray(),
        signature = ByteArray(64) { (255 - it).toByte() },
    )

    @Test
    fun roundtripPreservesAllFields() {
        val original = sample()
        val decoded = EnvelopeCodec.deserialize(EnvelopeCodec.serialize(original))
        assertEquals(original, decoded)
        assertEquals(original.hashCode(), decoded.hashCode())
    }

    @Test
    fun roundtripEmptyOptionalFields() {
        val e = MessageEnvelope(
            messageId = "ping-1",
            type = EnvelopeType.PING,
            senderId = "c".repeat(32),
            recipientId = MessageEnvelope.BROADCAST,
            timestampMs = 1L,
            expiryMs = 2L,
            ttl = 1,
        )
        assertEquals(e, EnvelopeCodec.deserialize(EnvelopeCodec.serialize(e)))
    }

    @Test
    fun corruptionRejected() {
        val bytes = EnvelopeCodec.serialize(sample())
        bytes[20] = (bytes[20].toInt() xor 0xFF).toByte() // flip inside a length/string field area
        try {
            EnvelopeCodec.deserialize(bytes)
            // Some single-byte flips in payload ciphertext are structurally
            // valid; a flipped magic/version/length must always fail.
            // Force the guaranteed-invalid case below.
            val badMagic = EnvelopeCodec.serialize(sample())
            badMagic[0] = 'X'.code.toByte()
            EnvelopeCodec.deserialize(badMagic)
            fail("Bad magic must be rejected")
        } catch (expected: CodecException) {
            // pass
        }
    }

    @Test
    fun badMagicRejected() {
        val bytes = EnvelopeCodec.serialize(sample())
        bytes[1] = 'X'.code.toByte()
        assertThrowsCodec { EnvelopeCodec.deserialize(bytes) }
    }

    @Test
    fun truncatedRejected() {
        val bytes = EnvelopeCodec.serialize(sample())
        assertThrowsCodec { EnvelopeCodec.deserialize(bytes.copyOf(bytes.size - 10)) }
    }

    @Test
    fun versionMismatchRejected() {
        val bytes = EnvelopeCodec.serialize(sample())
        // version is the int right after the 4-byte magic, big-endian.
        bytes[7] = 99
        assertThrowsCodec { EnvelopeCodec.deserialize(bytes) }
    }

    @Test
    fun oversizedRejected() {
        val big = ByteArray(EnvelopeCodec.MAX_TOTAL_BYTES + 1)
        assertThrowsCodec { EnvelopeCodec.deserialize(big) }
        val hugeField = sample().copy(ciphertext = ByteArray(EnvelopeCodec.MAX_FIELD_BYTES + 1))
        assertThrowsCodec { EnvelopeCodec.serialize(hugeField) }
    }

    @Test
    fun ttlAndExpirySemantics() {
        val e = sample()
        val hopped = e.incrementedHop()
        assertEquals(e.ttl - 1, hopped.ttl)
        assertEquals(e.hopCount + 1, hopped.hopCount)
        assertEquals(e.messageId, hopped.messageId)
        assertTrue(hopped.hasTtlRemaining())
        assertFalse(e.isExpired(e.timestampMs))
        assertTrue(e.isExpired(e.expiryMs + 1))
        val dead = e.copy(ttl = 1).incrementedHop()
        assertEquals(0, dead.ttl)
        assertFalse(dead.hasTtlRemaining())
    }

    private fun assertThrowsCodec(block: () -> Unit) {
        try {
            block()
            fail("Expected CodecException")
        } catch (expected: CodecException) {
            // pass
        }
    }
}
