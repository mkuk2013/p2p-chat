package com.p2pchat.messenger.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One voice-call attempt for the Calls tab (WhatsApp-style history).
 *
 * A row is written exactly once per attempt, when the call reaches a
 * terminal state — never for a call that did not happen. [direction]
 * is `INCOMING` / `OUTGOING`, [outcome] is `ANSWERED` / `MISSED` /
 * `REJECTED` / `FAILED` (names of the core-common CallDirection /
 * CallOutcome enums). [reason] is plain words for the user ("No
 * answer", "They declined", "Couldn't reach them — too far away") or
 * null when there is nothing worth adding. [durationMs] is the
 * talking time for answered calls and 0 for everything else.
 */
@Entity(tableName = "call_records")
data class CallRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerId: String,
    val peerName: String,
    val direction: String,
    val outcome: String,
    val reason: String? = null,
    val startedAtMs: Long,
    val durationMs: Long = 0
) {
    companion object {
        const val DIRECTION_INCOMING: String = "INCOMING"
        const val DIRECTION_OUTGOING: String = "OUTGOING"

        const val OUTCOME_ANSWERED: String = "ANSWERED"
        const val OUTCOME_MISSED: String = "MISSED"
        const val OUTCOME_REJECTED: String = "REJECTED"
        const val OUTCOME_FAILED: String = "FAILED"
    }
}
