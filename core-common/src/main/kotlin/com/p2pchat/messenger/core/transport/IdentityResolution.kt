package com.p2pchat.messenger.core.transport

/**
 * Bounded identity-resolution state machine for one BLE peer.
 *
 * Context (the v1.1.0 field hang): after GATT service discovery the
 * IDENTITY characteristic read could be silently dropped by the stack
 * (issued while the CCCD descriptor write was still in flight — Android
 * allows exactly one outstanding GATT operation) and nothing ever
 * retried or timed it out, so the peer stayed "Connecting…" forever.
 * This machine is the contract that makes that impossible: every read
 * attempt is bounded by [timeoutMs], failures (including transient
 * GATT status callbacks) are retried up to [maxAttempts], the last
 * attempt runs on a fully reset link (a wedged GATT state is not fixed
 * by re-reading on the same connection), and the terminal state is an
 * honest FAILED with the last failure recorded — never an endless wait.
 *
 * Pure Kotlin with an injected clock so the whole machine is unit
 * testable on the JVM. Not thread-safe: the transport confines all
 * calls to one lock.
 */
class IdentityResolver(
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    /** 1-based attempt number that must run on a freshly reset link. */
    val resetLinkOnAttempt: Int = DEFAULT_RESET_LINK_ON_ATTEMPT,
) {
    enum class Phase { IDLE, IN_FLIGHT, RESOLVED, FAILED }

    /** What the transport should do next after an event. */
    enum class Action {
        /** Nothing to do (already resolved / not started / terminal). */
        NONE,

        /** Issue the identity read on the current link now. */
        ISSUE_READ,

        /** Fully reset the link (disconnect + close + clear pending ops),
         * reconnect from scratch, and read on the fresh link. */
        RESET_LINK_THEN_READ,

        /** Budget exhausted — surface the terminal failure to the user. */
        GIVE_UP,
    }

    data class State(
        val phase: Phase = Phase.IDLE,
        /** 1-based attempt currently in flight (or last attempted). */
        val attempt: Int = 0,
        /** Last transient GATT status seen (e.g. 133), if any. */
        val lastGattStatus: Int? = null,
        /** Plain-words reason for the most recent failure, if any. */
        val lastFailure: String? = null,
        /** Deadline (same clock as the `nowMs` arguments) for the
         * in-flight attempt; [Long.MAX_VALUE] when not in flight. */
        val deadlineMs: Long = Long.MAX_VALUE,
    )

    var state: State = State()
        private set

    val isResolved: Boolean get() = state.phase == Phase.RESOLVED
    val isFailed: Boolean get() = state.phase == Phase.FAILED
    val isInFlight: Boolean get() = state.phase == Phase.IN_FLIGHT

    /**
     * Begin a fresh resolution session (new manual connect / user
     * retry): full attempt budget restored, attempt 1 issued on the
     * current link. If a session is already in flight this is a no-op
     * returning [Action.NONE]; if the previous session resolved or
     * failed, a new session starts.
     */
    fun beginSession(nowMs: Long): Action {
        if (state.phase == Phase.IN_FLIGHT) return Action.NONE
        state = State(
            phase = Phase.IN_FLIGHT,
            attempt = 1,
            deadlineMs = nowMs + timeoutMs,
        )
        return Action.ISSUE_READ
    }

    /**
     * Continue the SAME session on a fresh link after the transport
     * honoured [Action.RESET_LINK_THEN_READ]: the attempt counter is
     * preserved (this is what stops reset loops from running forever);
     * the next read must be issued by the caller once the new link's
     * discovery completes, so this parks the machine in IN_FLIGHT with
     * a deadline measured from the read actually being issued — call
     * [onReadIssued] at that moment to arm the timeout.
     */
    fun onLinkResetForRetry() {
        if (state.phase != Phase.IN_FLIGHT) return
        state = state.copy(deadlineMs = Long.MAX_VALUE)
    }

    /** Arm (or re-arm) the attempt deadline when a read is issued. */
    fun onReadIssued(nowMs: Long) {
        if (state.phase != Phase.IN_FLIGHT) return
        state = state.copy(deadlineMs = nowMs + timeoutMs)
    }

    /** The identity payload arrived (via the read OR the data-channel
     * announcement — both are the same proof). Idempotent. */
    fun onResolved() {
        state = state.copy(phase = Phase.RESOLVED, deadlineMs = Long.MAX_VALUE)
    }

    /**
     * A read attempt failed: callback with a non-SUCCESS [gattStatus],
     * the stack refusing to issue the read, or a decode-level failure.
     * Returns the follow-up action for the transport.
     */
    fun onReadFailed(nowMs: Long, gattStatus: Int?, reason: String): Action =
        failCurrentAttempt(nowMs, gattStatus, reason)

    /**
     * Clock tick: if the in-flight attempt's deadline has passed, the
     * attempt is failed with reason "timeout" and the follow-up action
     * is returned; otherwise [Action.NONE].
     */
    fun onTick(nowMs: Long): Action {
        if (state.phase != Phase.IN_FLIGHT) return Action.NONE
        if (state.deadlineMs == Long.MAX_VALUE) return Action.NONE
        if (nowMs < state.deadlineMs) return Action.NONE
        return failCurrentAttempt(nowMs, null, "timeout")
    }

    /** Abandon the session entirely (link torn down for good, manual
     * disconnect): back to IDLE with a clean budget for next time. */
    fun reset() {
        state = State()
    }

    private fun failCurrentAttempt(nowMs: Long, gattStatus: Int?, reason: String): Action {
        if (state.phase != Phase.IN_FLIGHT) return Action.NONE
        val failedAttempt = state.attempt
        val recorded = state.copy(
            lastGattStatus = gattStatus ?: state.lastGattStatus,
            lastFailure = reason,
        )
        if (failedAttempt >= maxAttempts) {
            state = recorded.copy(phase = Phase.FAILED, deadlineMs = Long.MAX_VALUE)
            return Action.GIVE_UP
        }
        val nextAttempt = failedAttempt + 1
        state = recorded.copy(
            attempt = nextAttempt,
            deadlineMs = nowMs + timeoutMs,
        )
        return if (nextAttempt >= resetLinkOnAttempt) {
            // The final attempt never runs on the link that already
            // failed us — that link may be wedged at the stack level.
            Action.RESET_LINK_THEN_READ
        } else {
            Action.ISSUE_READ
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 8_000L
        const val DEFAULT_MAX_ATTEMPTS: Int = 3
        const val DEFAULT_RESET_LINK_ON_ATTEMPT: Int = 3
    }
}

/**
 * Deterministic anti-collision rule for simultaneous initiation.
 *
 * Both phones run the same software, so both can hold a central (GATT
 * client) connection to each other at the same moment — the confirmed
 * v1.1.0 field trigger. The surviving central link of a pair must be
 * chosen identically by both sides without any negotiation: the device
 * with the lexicographically LOWER Bluetooth address is the pair's
 * designated central; the higher-address device yields (tears down its
 * own central connection cleanly) whenever both directions exist.
 * Identity still reaches the yielding side because identity payloads
 * are also announced over the surviving link's data channel.
 *
 * Address comparison uses the canonical uppercase "AA:BB:CC:DD:EE:FF"
 * form Android reports; both sides see the same two strings, so both
 * compute the same winner.
 */
object GattTieBreak {

    /** Android sometimes reports this placeholder for the local address. */
    private const val PLACEHOLDER_ADDRESS = "02:00:00:00:00:00"

    /** True when [localAddress] designates this device as the pair's
     * central (initiator). False when it should yield — or when the
     * local address is unknown/placeholder, in which case no
     * deterministic decision exists and nothing is yielded. */
    fun isDesignatedCentral(localAddress: String?, remoteAddress: String): Boolean {
        val local = normalize(localAddress) ?: return false
        val remote = normalize(remoteAddress) ?: return false
        return local < remote
    }

    /** True when this device must tear down its central link to
     * [remoteAddress]: both link directions exist AND the peer is the
     * designated central. Exactly one side of any colliding pair
     * returns true. */
    fun shouldYieldCentral(
        localAddress: String?,
        remoteAddress: String,
        hasCentralLink: Boolean,
        hasPeripheralLink: Boolean,
    ): Boolean {
        if (!hasCentralLink || !hasPeripheralLink) return false
        val local = normalize(localAddress) ?: return false
        val remote = normalize(remoteAddress) ?: return false
        return local > remote
    }

    private fun normalize(address: String?): String? {
        val value = address?.trim()?.uppercase() ?: return null
        if (value.isEmpty() || value == PLACEHOLDER_ADDRESS) return null
        return value
    }
}
