package com.p2pchat.messenger.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A known peer. Public keys only — private keys never live in Room,
 * they are held by [com.p2pchat.messenger.data.IdentityStore] (Keystore-wrapped).
 */
@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val peerId: String,
    val displayName: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val ed25519Public: ByteArray,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val x25519Public: ByteArray,
    /** Last known BLE MAC address, if any. BLE addresses can rotate. */
    val address: String? = null,
    val lastSeenMs: Long = 0L,
    /** -1 = unknown, 0 = self, 1 = directly reachable, >1 = via relay. */
    val hopsAway: Int = -1,
    val trusted: Boolean = false
) {
    // ByteArray properties need content-based equality for a data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PeerEntity) return false
        return peerId == other.peerId &&
            displayName == other.displayName &&
            ed25519Public.contentEquals(other.ed25519Public) &&
            x25519Public.contentEquals(other.x25519Public) &&
            address == other.address &&
            lastSeenMs == other.lastSeenMs &&
            hopsAway == other.hopsAway &&
            trusted == other.trusted
    }

    override fun hashCode(): Int {
        var result = peerId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + ed25519Public.contentHashCode()
        result = 31 * result + x25519Public.contentHashCode()
        result = 31 * result + (address?.hashCode() ?: 0)
        result = 31 * result + lastSeenMs.hashCode()
        result = 31 * result + hopsAway
        result = 31 * result + trusted.hashCode()
        return result
    }
}
