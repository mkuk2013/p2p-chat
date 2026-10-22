package com.p2pchat.messenger.core.call

/** Direction of a call, stored by name in the call-history table. */
enum class CallDirection { INCOMING, OUTGOING }

/** Final outcome of a call attempt, stored by name in the table. */
enum class CallOutcome { ANSWERED, MISSED, REJECTED, FAILED }

/**
 * Everything needed to persist one call-history row. Built by
 * [CallRecordBuilder] from the call controller's state transitions —
 * the app's recorder feeds it events and stores the returned draft.
 * [reason] is always plain words a non-technical user can read (or
 * null when there is nothing worth saying, e.g. a normal answered
 * call that simply ended).
 */
data class CallRecordDraft(
    val peerId: String,
    val peerName: String,
    val direction: CallDirection,
    val outcome: CallOutcome,
    val reason: String?,
    val startedAtMs: Long,
    val durationMs: Long,
)

/**
 * Translates the call controller's terminal reason strings into plain
 * words and classifies hard failures. The controller's strings are
 * the input contract; anything unrecognised is passed through as-is
 * (they are written to be readable, this only de-jargons the worst).
 */
object CallReasons {

    /** Plain-words version of a controller reason, or null when the
     *  reason adds nothing (a normal end). */
    fun plainReason(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "Call ended") return null
        return when {
            text == "Declined" -> "They declined"
            text == "No answer" -> "No answer"
            text == "Connection lost" -> "The connection was lost"
            text.contains("Play services") ->
                "This phone can't make nearby calls"
            text.contains("Microphone") || text.contains("microphone") ->
                "The microphone couldn't be opened"
            text.contains("not reachable") || text.contains("Could not reach") ||
                text.contains("out of range") ->
                "Couldn't reach them — too far away"
            text.contains("was lost") || text.contains("link failed") ->
                "The connection was lost"
            else -> text
        }
    }

    /** True when [raw] describes a technical failure rather than a
     *  normal call ending (declined / no answer / ended). */
    fun isFailure(raw: String?): Boolean {
        val text = raw ?: return false
        return text.contains("Play services") ||
            text.contains("Microphone") || text.contains("microphone") ||
            text.contains("not reachable") || text.contains("Could not reach") ||
            text.contains("Could not answer") || text.contains("was lost") ||
            text.contains("link failed")
    }
}

/**
 * Derives exactly one [CallRecordDraft] per call attempt from the
 * sequence of call-state events. Pure Kotlin + injected timestamps,
 * so the outcome matrix is unit-testable on the JVM.
 *
 * Contract: [onFinished] is called once with the terminal reason
 * (from an Ended/Failed state); an incoming call the user declines
 * goes straight to Idle in the controller, which the recorder
 * reports via [onRejectedByMe] followed by [onFinished] with a null
 * reason. Every finish resets the builder; a finish with no attempt
 * in flight returns null — no fabricated rows, ever.
 */
class CallRecordBuilder {

    private var peerId: String? = null
    private var peerName: String = ""
    private var direction: CallDirection = CallDirection.OUTGOING
    private var startedAtMs: Long = 0L
    private var answeredAtMs: Long? = null
    private var rejectedByMe: Boolean = false

    /** The peer of the attempt currently in flight, if any. */
    fun currentPeerId(): String? = peerId

    val inProgress: Boolean get() = peerId != null

    fun onOutgoingStarted(peerId: String, peerName: String?, nowMs: Long) {
        if (inProgress) return
        begin(peerId, peerName, CallDirection.OUTGOING, nowMs)
    }

    fun onIncomingStarted(peerId: String, peerName: String?, nowMs: Long) {
        if (inProgress) return
        begin(peerId, peerName, CallDirection.INCOMING, nowMs)
    }

    fun onAnswered(nowMs: Long) {
        if (inProgress && answeredAtMs == null) answeredAtMs = nowMs
    }

    fun onRejectedByMe() {
        if (inProgress && direction == CallDirection.INCOMING) rejectedByMe = true
    }

    fun onFinished(rawReason: String?, nowMs: Long): CallRecordDraft? {
        val id = peerId ?: return null
        val answeredAt = answeredAtMs
        val draft: CallRecordDraft
        if (answeredAt != null) {
            draft = CallRecordDraft(
                peerId = id,
                peerName = peerName,
                direction = direction,
                outcome = CallOutcome.ANSWERED,
                reason = CallReasons.plainReason(rawReason),
                startedAtMs = startedAtMs,
                durationMs = maxOf(0L, nowMs - answeredAt),
            )
        } else {
            val (outcome, reason) = when (direction) {
                CallDirection.OUTGOING -> when {
                    rawReason == "Declined" ->
                        CallOutcome.REJECTED to "They declined"
                    rawReason == "No answer" ->
                        CallOutcome.MISSED to "No answer"
                    rawReason == "Call ended" ->
                        CallOutcome.MISSED to "Cancelled"
                    CallReasons.isFailure(rawReason) ->
                        CallOutcome.FAILED to
                            (CallReasons.plainReason(rawReason) ?: "Couldn't connect")
                    else ->
                        CallOutcome.FAILED to
                            (CallReasons.plainReason(rawReason) ?: "Couldn't connect")
                }
                CallDirection.INCOMING -> when {
                    rejectedByMe ->
                        CallOutcome.REJECTED to "You declined"
                    rawReason != null && CallReasons.isFailure(rawReason) &&
                        (rawReason.contains("Microphone") ||
                            rawReason.contains("microphone") ||
                            rawReason.contains("Play services")) ->
                        CallOutcome.FAILED to CallReasons.plainReason(rawReason)
                    else ->
                        CallOutcome.MISSED to "Missed call"
                }
            }
            draft = CallRecordDraft(
                peerId = id,
                peerName = peerName,
                direction = direction,
                outcome = outcome,
                reason = reason,
                startedAtMs = startedAtMs,
                durationMs = 0L,
            )
        }
        reset()
        return draft
    }

    private fun begin(id: String, name: String?, dir: CallDirection, nowMs: Long) {
        peerId = id
        peerName = name?.takeIf { it.isNotBlank() } ?: "P2P Chat user"
        direction = dir
        startedAtMs = nowMs
        answeredAtMs = null
        rejectedByMe = false
    }

    private fun reset() {
        peerId = null
        peerName = ""
        startedAtMs = 0L
        answeredAtMs = null
        rejectedByMe = false
    }
}
