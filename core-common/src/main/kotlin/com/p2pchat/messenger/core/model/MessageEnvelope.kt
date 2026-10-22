package com.p2pchat.messenger.core.model

/**
 * Wire envelope for all mesh traffic. Relays forward this envelope without
 * being able to read [ciphertext] — it is an AES-256-GCM sealed payload
 * (see :core-crypto). [signature] is an Ed25519 signature by [senderId]
 * over the codec serialization of this envelope with signature emptied.
 */
enum class EnvelopeType { TEXT, ACK, PING, KEY_EXCHANGE, BLOB }

class MessageEnvelope(
    val version: Int = PROTOCOL_VERSION,
    val messageId: String,
    val type: EnvelopeType = EnvelopeType.TEXT,
    val senderId: String,
    val recipientId: String = BROADCAST,
    val timestampMs: Long,
    val expiryMs: Long,
    val ttl: Int = DEFAULT_TTL,
    val hopCount: Int = 0,
    val ephemeralPubKey: ByteArray = ByteArray(0),
    val nonce: ByteArray = ByteArray(0),
    val ciphertext: ByteArray = ByteArray(0),
    val signature: ByteArray = ByteArray(0),
) {
    fun incrementedHop(): MessageEnvelope = MessageEnvelope(
        version = version,
        messageId = messageId,
        type = type,
        senderId = senderId,
        recipientId = recipientId,
        timestampMs = timestampMs,
        expiryMs = expiryMs,
        ttl = (ttl - 1).coerceAtLeast(0),
        hopCount = hopCount + 1,
        ephemeralPubKey = ephemeralPubKey.copyOf(),
        nonce = nonce.copyOf(),
        ciphertext = ciphertext.copyOf(),
        signature = signature.copyOf(),
    )

    /** Copy helper (not a data class because of ByteArray fields). */
    fun copy(
        version: Int = this.version,
        messageId: String = this.messageId,
        type: EnvelopeType = this.type,
        senderId: String = this.senderId,
        recipientId: String = this.recipientId,
        timestampMs: Long = this.timestampMs,
        expiryMs: Long = this.expiryMs,
        ttl: Int = this.ttl,
        hopCount: Int = this.hopCount,
        ephemeralPubKey: ByteArray = this.ephemeralPubKey,
        nonce: ByteArray = this.nonce,
        ciphertext: ByteArray = this.ciphertext,
        signature: ByteArray = this.signature,
    ): MessageEnvelope = MessageEnvelope(
        version, messageId, type, senderId, recipientId, timestampMs, expiryMs,
        ttl, hopCount, ephemeralPubKey, nonce, ciphertext, signature,
    )

    fun isExpired(nowMs: Long): Boolean = nowMs > expiryMs
    fun hasTtlRemaining(): Boolean = ttl > 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEnvelope) return false
        return version == other.version &&
            messageId == other.messageId &&
            type == other.type &&
            senderId == other.senderId &&
            recipientId == other.recipientId &&
            timestampMs == other.timestampMs &&
            expiryMs == other.expiryMs &&
            ttl == other.ttl &&
            hopCount == other.hopCount &&
            ephemeralPubKey.contentEquals(other.ephemeralPubKey) &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var r = version
        r = 31 * r + messageId.hashCode()
        r = 31 * r + type.hashCode()
        r = 31 * r + senderId.hashCode()
        r = 31 * r + recipientId.hashCode()
        r = 31 * r + timestampMs.hashCode()
        r = 31 * r + expiryMs.hashCode()
        r = 31 * r + ttl
        r = 31 * r + hopCount
        r = 31 * r + ephemeralPubKey.contentHashCode()
        r = 31 * r + nonce.contentHashCode()
        r = 31 * r + ciphertext.contentHashCode()
        r = 31 * r + signature.contentHashCode()
        return r
    }

    override fun toString(): String =
        "MessageEnvelope(v=$version,id=$messageId,type=$type,from=$senderId,to=$recipientId," +
            "ts=$timestampMs,exp=$expiryMs,ttl=$ttl,hops=$hopCount,ct=${ciphertext.size}B,sig=${signature.size}B)"

    companion object {
        const val PROTOCOL_VERSION: Int = 1
        const val BROADCAST: String = "BROADCAST"
        const val DEFAULT_TTL: Int = 7
        const val DEFAULT_EXPIRY_MS: Long = 7L * 24 * 60 * 60 * 1000 // 7 days store-and-forward
    }
}
