package com.p2pchat.messenger.core.blob

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.security.MessageDigest

/**
 * Store-and-forward blob transfer (voice notes) on top of the mesh
 * envelope machinery. A blob is split into ordered [BlobChunk]s; every
 * chunk travels as the sealed plaintext of its own envelope, so chunks
 * inherit the mesh's encryption, signing, relaying, outbox retries and
 * per-envelope ACKs unchanged. The receiver reassembles with
 * [BlobReassembler] and only accepts the blob when every chunk is
 * present AND the SHA-256 of the reassembled bytes matches the hash
 * announced in every chunk frame — a partial or corrupted blob is
 * never delivered to the chat layer.
 *
 * Chunk frame layout (big-endian, DataOutputStream):
 *  magic "BLB1" (4) | blobId str | index int | total int |
 *  totalBytes long | sha256 bytes(32) | durationMs int | mime str |
 *  payload bytes
 */
class BlobChunk(
    val blobId: String,
    val index: Int,
    val total: Int,
    val totalBytes: Long,
    val sha256: ByteArray,
    val durationMs: Int,
    val mime: String,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlobChunk) return false
        return blobId == other.blobId && index == other.index && total == other.total &&
            totalBytes == other.totalBytes && sha256.contentEquals(other.sha256) &&
            durationMs == other.durationMs && mime == other.mime &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = blobId.hashCode()
        r = 31 * r + index
        r = 31 * r + total
        r = 31 * r + totalBytes.hashCode()
        r = 31 * r + sha256.contentHashCode()
        r = 31 * r + durationMs
        r = 31 * r + mime.hashCode()
        r = 31 * r + payload.contentHashCode()
        return r
    }

    override fun toString(): String =
        "BlobChunk(blob=$blobId,$index/$total,${payload.size}B of $totalBytes)"
}

object BlobChunkCodec {
    val MAGIC: ByteArray = byteArrayOf('B'.code.toByte(), 'L'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())

    /** Hard cap on one blob (a voice note). Enforced on split AND on receive. */
    const val MAX_BLOB_BYTES: Int = 600 * 1024

    /** Audio bytes per chunk frame (before sealing). */
    const val CHUNK_PAYLOAD_BYTES: Int = 8 * 1024

    const val SHA256_BYTES: Int = 32
    const val MAX_CHUNKS: Int = 1024

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun sha256Hex(data: ByteArray): String =
        sha256(data).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    fun bytesOfHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "Odd hex length" }
        return ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * Split [data] into chunk frames ready to be sealed as envelope
     * payloads, in order. @throws IllegalArgumentException if [data]
     * exceeds [MAX_BLOB_BYTES] or is empty.
     */
    fun split(
        blobId: String,
        data: ByteArray,
        durationMs: Int,
        mime: String,
        chunkPayloadBytes: Int = CHUNK_PAYLOAD_BYTES,
    ): List<ByteArray> {
        require(data.isNotEmpty()) { "Blob must not be empty" }
        require(data.size <= MAX_BLOB_BYTES) {
            "Blob too large: ${data.size} > $MAX_BLOB_BYTES"
        }
        require(chunkPayloadBytes > 0) { "chunkPayloadBytes must be > 0" }
        val hash = sha256(data)
        val total = (data.size + chunkPayloadBytes - 1) / chunkPayloadBytes
        require(total <= MAX_CHUNKS) { "Too many chunks: $total" }
        return (0 until total).map { i ->
            val from = i * chunkPayloadBytes
            val to = minOf(from + chunkPayloadBytes, data.size)
            encode(
                BlobChunk(
                    blobId = blobId,
                    index = i,
                    total = total,
                    totalBytes = data.size.toLong(),
                    sha256 = hash,
                    durationMs = durationMs,
                    mime = mime,
                    payload = data.copyOfRange(from, to),
                )
            )
        }
    }

    fun encode(chunk: BlobChunk): ByteArray {
        val baos = ByteArrayOutputStream()
        DataOutputStream(baos).use { out ->
            out.write(MAGIC)
            writeString(out, chunk.blobId)
            out.writeInt(chunk.index)
            out.writeInt(chunk.total)
            out.writeLong(chunk.totalBytes)
            require(chunk.sha256.size == SHA256_BYTES) { "sha256 must be 32 bytes" }
            out.write(chunk.sha256)
            out.writeInt(chunk.durationMs)
            writeString(out, chunk.mime)
            out.writeInt(chunk.payload.size)
            out.write(chunk.payload)
        }
        return baos.toByteArray()
    }

    fun decode(frame: ByteArray): BlobChunk {
        try {
            DataInputStream(ByteArrayInputStream(frame)).use { input ->
                val magic = ByteArray(4)
                input.readFully(magic)
                if (!magic.contentEquals(MAGIC)) {
                    throw IllegalArgumentException("Bad blob frame magic")
                }
                val blobId = readString(input)
                val index = input.readInt()
                val total = input.readInt()
                val totalBytes = input.readLong()
                if (total !in 1..MAX_CHUNKS) {
                    throw IllegalArgumentException("Invalid chunk total: $total")
                }
                if (index !in 0 until total) {
                    throw IllegalArgumentException("Invalid chunk index $index of $total")
                }
                if (totalBytes !in 1..MAX_BLOB_BYTES) {
                    throw IllegalArgumentException("Invalid blob size: $totalBytes")
                }
                val sha = ByteArray(SHA256_BYTES)
                input.readFully(sha)
                val durationMs = input.readInt()
                val mime = readString(input)
                val payloadLen = input.readInt()
                if (payloadLen < 0 || payloadLen > MAX_BLOB_BYTES) {
                    throw IllegalArgumentException("Invalid payload length: $payloadLen")
                }
                if (payloadLen > input.available()) {
                    throw IllegalArgumentException("Payload exceeds remaining frame bytes")
                }
                val payload = ByteArray(payloadLen)
                input.readFully(payload)
                if (input.available() != 0) {
                    throw IllegalArgumentException("Trailing bytes in blob frame")
                }
                return BlobChunk(blobId, index, total, totalBytes, sha, durationMs, mime, payload)
            }
        } catch (e: EOFException) {
            throw IllegalArgumentException("Truncated blob frame", e)
        }
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256) { "Blob frame string too large" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val len = input.readInt()
        if (len < 0 || len > 256) throw IllegalArgumentException("Invalid string length: $len")
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}

/**
 * Reassembles one blob from its chunks. Chunks may arrive out of order
 * and duplicated (multi-hop floods + retries); duplicates are ignored,
 * conflicting metadata (same blobId, different total/size/hash) is
 * rejected without corrupting the partial state, and [assemble]
 * returns the bytes ONLY when every chunk is present and the SHA-256
 * of the result matches the announced hash — otherwise null.
 */
class BlobReassembler {

    enum class AddResult {
        /** Chunk stored; blob still incomplete. */
        ACCEPTED,

        /** This chunk completed the blob (integrity still to verify via [assemble]). */
        COMPLETED,

        /** Same index already present — ignored. */
        DUPLICATE,

        /** Chunk conflicts with the metadata learned from earlier chunks. */
        REJECTED_CONFLICT,

        /** Chunk is structurally invalid for this blob (index/size). */
        REJECTED_INVALID,
    }

    private var blobId: String? = null
    private var total: Int = -1
    private var totalBytes: Long = -1L
    private var sha256: ByteArray? = null
    private var durationMs: Int = 0
    private var mime: String = ""
    private val chunks = mutableMapOf<Int, ByteArray>()

    val id: String? get() = blobId
    val totalChunks: Int get() = if (total < 0) 0 else total
    val receivedCount: Int get() = chunks.size
    val expectedBytes: Long get() = totalBytes
    val expectedSha256: ByteArray? get() = sha256?.copyOf()
    val mediaDurationMs: Int get() = durationMs
    val mediaMime: String get() = mime

    fun isComplete(): Boolean = total > 0 && chunks.size == total

    fun missingIndices(): List<Int> {
        if (total < 0) return emptyList()
        return (0 until total).filter { it !in chunks }
    }

    @Synchronized
    fun add(chunk: BlobChunk): AddResult {
        // A non-final chunk must be exactly CHUNK_PAYLOAD_BYTES... senders
        // may use a different chunking size, so the only hard rules are:
        // payload non-empty and never larger than the whole blob.
        if (chunk.payload.isEmpty() || chunk.payload.size.toLong() > chunk.totalBytes) {
            return AddResult.REJECTED_INVALID
        }
        val knownId = blobId
        if (knownId == null) {
            blobId = chunk.blobId
            total = chunk.total
            totalBytes = chunk.totalBytes
            sha256 = chunk.sha256.copyOf()
            durationMs = chunk.durationMs
            mime = chunk.mime
        } else {
            if (chunk.blobId != knownId ||
                chunk.total != total ||
                chunk.totalBytes != totalBytes ||
                !chunk.sha256.contentEquals(sha256!!)
            ) {
                return AddResult.REJECTED_CONFLICT
            }
        }
        if (chunks.containsKey(chunk.index)) return AddResult.DUPLICATE
        chunks[chunk.index] = chunk.payload.copyOf()
        return if (isComplete()) AddResult.COMPLETED else AddResult.ACCEPTED
    }

    /**
     * The reassembled bytes, or null when incomplete, when the total
     * size disagrees, or when the SHA-256 check fails (corrupted or
     * substituted chunk). Never returns unverified bytes.
     */
    @Synchronized
    fun assemble(): ByteArray? {
        if (!isComplete()) return null
        val out = ByteArray(totalBytes.toInt())
        var offset = 0
        for (i in 0 until total) {
            val part = chunks.getValue(i)
            if (offset + part.size > out.size) return null
            part.copyInto(out, offset)
            offset += part.size
        }
        if (offset != out.size) return null
        val actual = BlobChunkCodec.sha256(out)
        if (!actual.contentEquals(sha256!!)) return null
        return out
    }
}
