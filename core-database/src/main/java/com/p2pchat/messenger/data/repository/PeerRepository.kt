package com.p2pchat.messenger.data.repository

import com.p2pchat.messenger.data.P2PDatabase
import com.p2pchat.messenger.data.entity.PeerEntity
import com.p2pchat.messenger.mesh.PeerInfo
import kotlinx.coroutines.flow.Flow

/**
 * Peers known to this device. The single source of truth is Room; BLE
 * sightings and mesh peer-table updates are merged in via [recordPeerInfo]
 * and [upsert], and the UI observes [peers].
 */
class PeerRepository(
    private val db: P2PDatabase
) {
    val peers: Flow<List<PeerEntity>> = db.peerDao().observeAll()

    fun observePeer(peerId: String): Flow<PeerEntity?> = db.peerDao().observeById(peerId)

    suspend fun getPeer(peerId: String): PeerEntity? = db.peerDao().getById(peerId)

    suspend fun getByAddress(address: String): PeerEntity? = db.peerDao().getByAddress(address)

    suspend fun upsert(peer: PeerEntity) = db.peerDao().upsert(peer)

    /** Merge a mesh peer-table entry, preserving any keys/trust already known. */
    suspend fun recordPeerInfo(info: PeerInfo) {
        val existing = db.peerDao().getById(info.peerId)
        if (existing == null) {
            db.peerDao().upsert(
                PeerEntity(
                    peerId = info.peerId,
                    displayName = info.displayName ?: info.peerId.take(8),
                    ed25519Public = info.ed25519Public ?: ByteArray(0),
                    x25519Public = info.x25519Public ?: ByteArray(0),
                    address = info.address,
                    lastSeenMs = info.lastSeenMs,
                    hopsAway = info.hopsAway,
                    trusted = false
                )
            )
        } else {
            db.peerDao().updateLastSeen(
                peerId = info.peerId,
                address = info.address ?: existing.address,
                lastSeenMs = info.lastSeenMs,
                hopsAway = info.hopsAway
            )
            // Local vals: PeerInfo's public properties live in another
            // module, so the compiler cannot smart-cast them directly.
            val ed25519Public = info.ed25519Public
            val x25519Public = info.x25519Public
            if (ed25519Public != null && x25519Public != null) {
                db.peerDao().upsert(
                    existing.copy(
                        displayName = info.displayName ?: existing.displayName,
                        ed25519Public = ed25519Public,
                        x25519Public = x25519Public,
                        address = info.address ?: existing.address,
                        lastSeenMs = info.lastSeenMs,
                        hopsAway = info.hopsAway
                    )
                )
            }
        }
    }

    suspend fun setTrusted(peerId: String, trusted: Boolean) =
        db.peerDao().setTrusted(peerId, trusted)

    suspend fun delete(peerId: String) = db.peerDao().deleteById(peerId)
}
