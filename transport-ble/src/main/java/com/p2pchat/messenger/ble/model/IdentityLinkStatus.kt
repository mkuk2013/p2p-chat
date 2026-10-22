package com.p2pchat.messenger.ble.model

/** Phase of the identity exchange for one device address. */
enum class IdentityPhase {
    /** A read attempt is in flight or scheduled (bounded — see resolver). */
    RESOLVING,

    /** The peer's verified identity payload has been received. */
    RESOLVED,

    /** The bounded attempt budget was exhausted; [IdentityLinkStatus.message]
     * says why, in plain words. Retrying is an explicit user action. */
    FAILED,
}

/**
 * User-visible, diagnostics-grade state of the identity exchange for one
 * device address. [message] is written for the person holding the phone
 * ("Connected — reading identity (try 2/3)…"), never a raw code, and is
 * also mirrored into the crash-report breadcrumb trail.
 */
data class IdentityLinkStatus(
    val phase: IdentityPhase,
    /** 1-based attempt in flight / last attempted (0 = not started). */
    val attempt: Int = 0,
    val maxAttempts: Int = 0,
    val message: String,
)
