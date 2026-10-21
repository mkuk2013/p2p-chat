package com.p2pchat.messenger.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import com.p2pchat.messenger.data.entity.PeerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeenMs DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE peerId = :peerId")
    fun observeById(peerId: String): Flow<PeerEntity?>

    @Query("SELECT * FROM peers WHERE peerId = :peerId")
    suspend fun getById(peerId: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE address = :address LIMIT 1")
    suspend fun getByAddress(address: String): PeerEntity?

    @Upsert
    suspend fun upsert(peer: PeerEntity)

    @Upsert
    suspend fun upsertAll(peers: List<PeerEntity>)

    @Query(
        "UPDATE peers SET lastSeenMs = :lastSeenMs, address = :address, hopsAway = :hopsAway " +
            "WHERE peerId = :peerId"
    )
    suspend fun updateLastSeen(peerId: String, address: String?, lastSeenMs: Long, hopsAway: Int)

    @Query("UPDATE peers SET trusted = :trusted WHERE peerId = :peerId")
    suspend fun setTrusted(peerId: String, trusted: Boolean)

    @Delete
    suspend fun delete(peer: PeerEntity)

    @Query("DELETE FROM peers WHERE peerId = :peerId")
    suspend fun deleteById(peerId: String)

    @Query("SELECT COUNT(*) FROM peers")
    fun observeCount(): Flow<Int>
}
