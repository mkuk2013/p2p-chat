package com.p2pchat.messenger.data.repository

import com.p2pchat.messenger.core.call.CallRecordDraft
import com.p2pchat.messenger.data.P2PDatabase
import com.p2pchat.messenger.data.entity.CallRecordEntity
import kotlinx.coroutines.flow.Flow

/**
 * Call history for the Calls tab. The app's call recorder derives a
 * [CallRecordDraft] from the call controller's state transitions
 * (core-common's CallRecordBuilder owns the outcome logic) and stores
 * it here exactly once per attempt; this class only maps and persists.
 */
class CallHistoryRepository(private val database: P2PDatabase) {

    fun observeCalls(): Flow<List<CallRecordEntity>> =
        database.callRecordDao().observeAll()

    suspend fun record(draft: CallRecordDraft) {
        database.callRecordDao().insert(
            CallRecordEntity(
                peerId = draft.peerId,
                peerName = draft.peerName,
                direction = draft.direction.name,
                outcome = draft.outcome.name,
                reason = draft.reason,
                startedAtMs = draft.startedAtMs,
                durationMs = draft.durationMs
            )
        )
    }
}
