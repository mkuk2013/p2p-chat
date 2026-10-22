package com.p2pchat.messenger.core

import com.p2pchat.messenger.core.transport.GattTieBreak
import com.p2pchat.messenger.core.transport.IdentityResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v1.1.0 field hang, as tests: an identity read that is dropped,
 * times out, or fails with a transient GATT status must retry within a
 * bounded budget, escalate the final attempt to a full link reset, and
 * then fail honestly — never wait forever.
 */
class IdentityResolutionTest {

    private val t0 = 100_000L

    @Test
    fun `begin session issues the first read with a deadline`() {
        val r = IdentityResolver()
        val action = r.beginSession(t0)
        assertEquals(IdentityResolver.Action.ISSUE_READ, action)
        assertTrue(r.isInFlight)
        assertEquals(1, r.state.attempt)
        assertEquals(t0 + IdentityResolver.DEFAULT_TIMEOUT_MS, r.state.deadlineMs)
    }

    @Test
    fun `second begin while in flight is a no-op`() {
        val r = IdentityResolver()
        r.beginSession(t0)
        assertEquals(IdentityResolver.Action.NONE, r.beginSession(t0 + 10))
        assertEquals(1, r.state.attempt)
    }

    @Test
    fun `successful read resolves and stops the clock`() {
        val r = IdentityResolver()
        r.beginSession(t0)
        r.onResolved()
        assertTrue(r.isResolved)
        // No amount of ticking may un-resolve or fail a resolved peer.
        assertEquals(IdentityResolver.Action.NONE, r.onTick(t0 + 10_000_000L))
        assertTrue(r.isResolved)
    }

    @Test
    fun `timeout retries on the same link for attempt two`() {
        val r = IdentityResolver()
        r.beginSession(t0)
        // One ms before the deadline: nothing yet.
        assertEquals(IdentityResolver.Action.NONE, r.onTick(t0 + 7_999L))
        val action = r.onTick(t0 + 8_000L)
        assertEquals(IdentityResolver.Action.ISSUE_READ, action)
        assertTrue(r.isInFlight)
        assertEquals(2, r.state.attempt)
        assertEquals("timeout", r.state.lastFailure)
    }

    @Test
    fun `final attempt escalates to a full link reset`() {
        val r = IdentityResolver()
        r.beginSession(t0)
        r.onTick(t0 + 8_000L) // attempt 1 times out -> retry read
        val action = r.onTick(t0 + 16_000L) // attempt 2 times out
        assertEquals(IdentityResolver.Action.RESET_LINK_THEN_READ, action)
        assertEquals(3, r.state.attempt)
        assertTrue(r.isInFlight)
    }

    @Test
    fun `reset parks the deadline until the fresh read is issued`() {
        val r = IdentityResolver()
        r.beginSession(t0)
        r.onTick(t0 + 8_000L)
        r.onTick(t0 + 16_000L) // -> RESET_LINK_THEN_READ for attempt 3
        r.onLinkResetForRetry()
        // Reconnect + discovery may take a while; no timeout may fire
        // while no read is actually in flight.
        assertEquals(IdentityResolver.Action.NONE, r.onTick(t0 + 10_000_000L))
        // Once the read is issued on the fresh link, the clock re-arms.
        r.onReadIssued(t0 + 20_000L)
        assertEquals(t0 + 28_000L, r.state.deadlineMs)
        val action = r.onTick(t0 + 28_000L)
        assertEquals(IdentityResolver.Action.GIVE_UP, action)
        assertTrue(r.isFailed)
        assertEquals("timeout", r.state.lastFailure)
    }

    @Test
    fun `transient gatt error status is tolerated and recorded`() {
        val r = IdentityResolver()
        r.beginSession(t0)
        // GATT_ERROR 133 — the classic transient stack failure.
        val action = r.onReadFailed(t0 + 100, gattStatus = 133, reason = "GATT status 133")
        assertEquals(IdentityResolver.Action.ISSUE_READ, action)
        assertEquals(133, r.state.lastGattStatus)
        assertEquals("GATT status 133", r.state.lastFailure)
        assertEquals(2, r.state.attempt)
    }

    @Test
    fun `budget exhaustion gives up with the last status kept`() {
        val r = IdentityResolver()
        r.beginSession(t0)
        r.onReadFailed(t0 + 1, gattStatus = 133, reason = "GATT status 133")
        val second = r.onReadFailed(t0 + 2, gattStatus = 8, reason = "GATT status 8")
        assertEquals(IdentityResolver.Action.RESET_LINK_THEN_READ, second)
        r.onLinkResetForRetry()
        r.onReadIssued(t0 + 3)
        val third = r.onReadFailed(t0 + 4, gattStatus = 133, reason = "GATT status 133")
        assertEquals(IdentityResolver.Action.GIVE_UP, third)
        assertTrue(r.isFailed)
        assertEquals(133, r.state.lastGattStatus)
        // Terminal: further failures/ticks change nothing.
        assertEquals(IdentityResolver.Action.NONE, r.onReadFailed(t0 + 5, 1, "late"))
        assertEquals(IdentityResolver.Action.NONE, r.onTick(t0 + 10_000_000L))
    }

    @Test
    fun `announcement after failure still resolves`() {
        val r = IdentityResolver()
        r.beginSession(t0)
        r.onReadFailed(t0 + 1, null, "timeout")
        r.onReadFailed(t0 + 2, null, "timeout")
        r.onLinkResetForRetry()
        r.onReadIssued(t0 + 3)
        r.onReadFailed(t0 + 4, null, "timeout")
        assertTrue(r.isFailed)
        // The data-channel announcement is the same proof as the read.
        r.onResolved()
        assertTrue(r.isResolved)
    }

    @Test
    fun `reset returns to idle with a clean budget`() {
        val r = IdentityResolver()
        r.beginSession(t0)
        r.onReadFailed(t0 + 1, 133, "GATT status 133")
        r.reset()
        assertEquals(IdentityResolver.Phase.IDLE, r.state.phase)
        assertEquals(0, r.state.attempt)
        assertEquals(IdentityResolver.Action.ISSUE_READ, r.beginSession(t0 + 50))
        assertEquals(1, r.state.attempt)
    }
}

class GattTieBreakTest {

    private val low = "0A:11:22:33:44:55"
    private val high = "F1:11:22:33:44:55"

    @Test
    fun `lower address is the designated central`() {
        assertTrue(GattTieBreak.isDesignatedCentral(low, high))
        assertFalse(GattTieBreak.isDesignatedCentral(high, low))
    }

    @Test
    fun `exactly one side yields when both link directions exist`() {
        val lowYields = GattTieBreak.shouldYieldCentral(
            low, high, hasCentralLink = true, hasPeripheralLink = true
        )
        val highYields = GattTieBreak.shouldYieldCentral(
            high, low, hasCentralLink = true, hasPeripheralLink = true
        )
        assertFalse(lowYields)
        assertTrue(highYields)
    }

    @Test
    fun `no yield without a collision`() {
        assertFalse(
            GattTieBreak.shouldYieldCentral(
                high, low, hasCentralLink = true, hasPeripheralLink = false
            )
        )
        assertFalse(
            GattTieBreak.shouldYieldCentral(
                high, low, hasCentralLink = false, hasPeripheralLink = true
            )
        )
    }

    @Test
    fun `unknown or placeholder local address never yields`() {
        assertFalse(
            GattTieBreak.shouldYieldCentral(
                null, low, hasCentralLink = true, hasPeripheralLink = true
            )
        )
        assertFalse(
            GattTieBreak.shouldYieldCentral(
                "02:00:00:00:00:00", low,
                hasCentralLink = true, hasPeripheralLink = true
            )
        )
        assertFalse(GattTieBreak.isDesignatedCentral(null, low))
        assertFalse(GattTieBreak.isDesignatedCentral("02:00:00:00:00:00", low))
    }

    @Test
    fun `comparison is case insensitive`() {
        assertTrue(GattTieBreak.isDesignatedCentral("0a:11:22:33:44:55", high))
        assertTrue(
            GattTieBreak.shouldYieldCentral(
                "f1:11:22:33:44:55", low,
                hasCentralLink = true, hasPeripheralLink = true
            )
        )
    }
}
