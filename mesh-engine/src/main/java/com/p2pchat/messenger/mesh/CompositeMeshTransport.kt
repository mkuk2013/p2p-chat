package com.p2pchat.messenger.mesh

/**
 * Fans the router's sends out over several point-to-point transports
 * (e.g. the BLE GATT transport plus a Google Nearby Connections direct
 * link). A send succeeds if ANY transport carries it; broadcasts go to
 * every transport. Transports are tried in constructor order — put the
 * fastest direct link first.
 *
 * This stays a pure fan-out: routing, dedup and delivery truth remain the
 * MeshRouter's job, so multi-transport duplication is harmless (the
 * receiver dedups by messageId and ACKs the first copy).
 */
class CompositeMeshTransport(
    private val transports: List<MeshTransport>,
) : MeshTransport {

    override fun connectedPeerIds(): Set<String> =
        transports.fold(mutableSetOf()) { acc, t -> acc.apply { addAll(t.connectedPeerIds()) } }

    override suspend fun sendTo(peerId: String, bytes: ByteArray): Boolean {
        for (t in transports) {
            if (peerId in t.connectedPeerIds() && t.sendTo(peerId, bytes)) return true
        }
        return false
    }

    override fun broadcastExcept(excludePeerId: String?, bytes: ByteArray) {
        for (t in transports) t.broadcastExcept(excludePeerId, bytes)
    }
}
