package com.p2pchat.messenger.core.model

import java.security.MessageDigest

/**
 * PeerId = lowercase hex of the first 16 bytes of SHA-256(Ed25519 public key).
 * 32 hex characters, stable for a given identity key.
 */
object PeerIds {
    private val HEX = "0123456789abcdef".toCharArray()

    fun fromPublicKey(publicKey: ByteArray): String {
        require(publicKey.isNotEmpty()) { "publicKey must not be empty" }
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b shr 4])
            sb.append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    fun isValid(peerId: String): Boolean {
        if (peerId.length != 32) return false
        return peerId.all { it in '0'..'9' || it in 'a'..'f' }
    }
}
