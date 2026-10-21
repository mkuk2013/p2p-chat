package com.p2pchat.messenger.crypto

/**
 * A local peer identity: a long-term Ed25519 signing keypair (its public
 * key defines the PeerId) plus a long-term X25519 key-agreement keypair.
 *
 * SECURITY: Private keys in this object are raw bytes in memory only.
 * At rest they MUST be stored encrypted — the data layer wraps them with
 * a key held in the Android Keystore (see SecureKeyStorage) before writing
 * to Room/files. Private keys are never hardcoded, never logged, and never
 * placed in an envelope. This class is produced/consumed by [CryptoManager].
 */
class Identity(
    val peerId: String,
    val displayName: String,
    val ed25519Public: ByteArray,
    val x25519Public: ByteArray,
    val ed25519Private: ByteArray,
    val x25519Private: ByteArray,
) {
    /** Public-only view safe to share with peers. */
    fun publicDescriptor(): String = "$peerId:$displayName"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Identity) return false
        return peerId == other.peerId &&
            displayName == other.displayName &&
            ed25519Public.contentEquals(other.ed25519Public) &&
            x25519Public.contentEquals(other.x25519Public) &&
            ed25519Private.contentEquals(other.ed25519Private) &&
            x25519Private.contentEquals(other.x25519Private)
    }

    override fun hashCode(): Int {
        var r = peerId.hashCode()
        r = 31 * r + displayName.hashCode()
        r = 31 * r + ed25519Public.contentHashCode()
        r = 31 * r + x25519Public.contentHashCode()
        r = 31 * r + ed25519Private.contentHashCode()
        r = 31 * r + x25519Private.contentHashCode()
        return r
    }

    /** Deliberately redacts key material. */
    override fun toString(): String = "Identity(peerId=$peerId, name=$displayName)"
}
