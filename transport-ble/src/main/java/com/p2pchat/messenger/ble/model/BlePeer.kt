package com.p2pchat.messenger.ble.model

/**
 * A nearby device discovered by BLE scanning.
 *
 * [peerId] is null until the peer's identity has been resolved — either from
 * the IDENTITY characteristic after connecting, or by the app layer matching
 * the 8-byte identity hint carried in the advertisement's service data
 * (first 8 bytes of SHA-256(peerId)). A non-null [peerId] is therefore
 * always *resolved* identity, never a guess. Scanners must not fabricate one.
 */
data class BlePeer(
    val deviceAddress: String,
    val peerId: String? = null,
    val displayName: String? = null,
    val rssi: Int = 0,
    val lastSeenMs: Long = 0L,
    val connectable: Boolean = true,
    /** First 8 bytes of SHA-256(peerId) from advertisement service data, if present. */
    val identityHint: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BlePeer) return false
        return deviceAddress == other.deviceAddress &&
            peerId == other.peerId &&
            displayName == other.displayName &&
            rssi == other.rssi &&
            lastSeenMs == other.lastSeenMs &&
            connectable == other.connectable &&
            identityHint.contentEquals(other.identityHint)
    }

    override fun hashCode(): Int {
        var r = deviceAddress.hashCode()
        r = 31 * r + (peerId?.hashCode() ?: 0)
        r = 31 * r + (displayName?.hashCode() ?: 0)
        r = 31 * r + rssi
        r = 31 * r + lastSeenMs.hashCode()
        r = 31 * r + connectable.hashCode()
        r = 31 * r + identityHint.contentHashCode()
        return r
    }
}

/** Connection lifecycle for one device address. */
enum class ConnState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,      // GATT link up, service discovery not finished yet
    DISCOVERING,
    READY,          // discovered, notifications on, identity read attempted — usable for send()
    DISCONNECTING,
    FAILED,
}

/** A fully reassembled inbound payload from [address]. */
class IncomingData(
    val address: String,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IncomingData) return false
        return address == other.address && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * address.hashCode() + data.contentHashCode()

    override fun toString(): String = "IncomingData($address, ${data.size}B)"
}
