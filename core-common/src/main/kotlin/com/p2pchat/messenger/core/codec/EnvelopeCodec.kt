package com.p2pchat.messenger.core.codec

import com.p2pchat.messenger.core.model.EnvelopeType
import com.p2pchat.messenger.core.model.MessageEnvelope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

class CodecException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Binary codec for [MessageEnvelope]. Layout (big-endian, DataOutputStream):
 *  magic "P2P1" (4) | version int | messageId str | type str | senderId str |
 *  recipientId str | timestampMs long | expiryMs long | ttl int | hopCount int |
 *  ephemeralPubKey bytes | nonce bytes | ciphertext bytes | signature bytes
 * Strings/byte arrays are int-length-prefixed UTF-8 / raw bytes.
 * Any field > [MAX_FIELD_BYTES] or total > [MAX_TOTAL_BYTES] is rejected.
 */
object EnvelopeCodec {
    val MAGIC: ByteArray = byteArrayOf('P'.code.toByte(), '2'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
    const val MAX_FIELD_BYTES: Int = 512 * 1024
    const val MAX_TOTAL_BYTES: Int = 512 * 1024
    const val MAX_STRING_CHARS: Int = 4096

    fun serialize(envelope: MessageEnvelope): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.write(MAGIC)
            out.writeInt(envelope.version)
            writeString(out, envelope.messageId)
            writeString(out, envelope.type.name)
            writeString(out, envelope.senderId)
            writeString(out, envelope.recipientId)
            out.writeLong(envelope.timestampMs)
            out.writeLong(envelope.expiryMs)
            out.writeInt(envelope.ttl)
            out.writeInt(envelope.hopCount)
            writeBytes(out, envelope.ephemeralPubKey)
            writeBytes(out, envelope.nonce)
            writeBytes(out, envelope.ciphertext)
            writeBytes(out, envelope.signature)
        }
        val bytes = baos.toByteArray()
        if (bytes.size > MAX_TOTAL_BYTES) {
            throw CodecException("Serialized envelope too large: ${bytes.size} > $MAX_TOTAL_BYTES")
        }
        return bytes
    }

    fun deserialize(data: ByteArray): MessageEnvelope {
        if (data.size > MAX_TOTAL_BYTES) throw CodecException("Envelope too large: ${data.size}")
        if (data.size < MAGIC.size + 4) throw CodecException("Envelope too short")
        try {
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                val magic = ByteArray(4)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC)) throw CodecException("Bad magic")
                val version = input.readInt()
                if (version != MessageEnvelope.PROTOCOL_VERSION) {
                    throw CodecException("Unsupported protocol version: $version")
                }
                val messageId = readString(input)
                val typeName = readString(input)
                val type = try {
                    EnvelopeType.valueOf(typeName)
                } catch (e: IllegalArgumentException) {
                    throw CodecException("Unknown envelope type: $typeName", e)
                }
                val senderId = readString(input)
                val recipientId = readString(input)
                val timestampMs = input.readLong()
                val expiryMs = input.readLong()
                val ttl = input.readInt()
                val hopCount = input.readInt()
                if (ttl < 0 || ttl > 255) throw CodecException("Invalid ttl: $ttl")
                if (hopCount < 0 || hopCount > 255) throw CodecException("Invalid hopCount: $hopCount")
                val ephemeralPubKey = readBytes(input)
                val nonce = readBytes(input)
                val ciphertext = readBytes(input)
                val signature = readBytes(input)
                if (input.available() != 0) throw CodecException("Trailing bytes: ${input.available()}")
                return MessageEnvelope(
                    version = version,
                    messageId = messageId,
                    type = type,
                    senderId = senderId,
                    recipientId = recipientId,
                    timestampMs = timestampMs,
                    expiryMs = expiryMs,
                    ttl = ttl,
                    hopCount = hopCount,
                    ephemeralPubKey = ephemeralPubKey,
                    nonce = nonce,
                    ciphertext = ciphertext,
                    signature = signature,
                )
            }
        } catch (e: CodecException) {
            throw e
        } catch (e: EOFException) {
            throw CodecException("Truncated envelope", e)
        } catch (e: Exception) {
            throw CodecException("Corrupt envelope: ${e.message}", e)
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_STRING_CHARS) throw CodecException("String field too large")
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val len = input.readInt()
        if (len < 0 || len > MAX_STRING_CHARS) throw CodecException("Invalid string length: $len")
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun writeBytes(out: DataOutputStream, value: ByteArray) {
        if (value.size > MAX_FIELD_BYTES) throw CodecException("Byte field too large: ${value.size}")
        out.writeInt(value.size)
        out.write(value)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val len = input.readInt()
        if (len < 0 || len > MAX_FIELD_BYTES) throw CodecException("Invalid byte field length: $len")
        if (len > input.available()) throw CodecException("Byte field exceeds remaining data")
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return bytes
    }
}
