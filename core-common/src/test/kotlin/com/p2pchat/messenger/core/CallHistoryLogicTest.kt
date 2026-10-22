package com.p2pchat.messenger.core

import com.p2pchat.messenger.core.call.CallDirection
import com.p2pchat.messenger.core.call.CallOutcome
import com.p2pchat.messenger.core.call.CallReasons
import com.p2pchat.messenger.core.call.CallRecordBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call-history outcome matrix: one honest row per attempt, with
 * plain-words reasons. These drafts are exactly what gets persisted.
 */
class CallHistoryLogicTest {

    private val t0 = 1_000_000L

    @Test
    fun `outgoing answered call records its duration`() {
        val b = CallRecordBuilder()
        b.onOutgoingStarted("peer-a", "Ayesha", t0)
        b.onAnswered(t0 + 3_000)
        val draft = b.onFinished("Call ended", t0 + 63_000)
        assertEquals(CallOutcome.ANSWERED, draft!!.outcome)
        assertEquals(CallDirection.OUTGOING, draft.direction)
        assertEquals(60_000L, draft.durationMs)
        assertEquals("Ayesha", draft.peerName)
        // A normal end adds no reason line.
        assertNull(draft.reason)
    }

    @Test
    fun `outgoing call with no answer is missed`() {
        val b = CallRecordBuilder()
        b.onOutgoingStarted("peer-a", "Ayesha", t0)
        val draft = b.onFinished("No answer", t0 + 45_000)
        assertEquals(CallOutcome.MISSED, draft!!.outcome)
        assertEquals("No answer", draft.reason)
        assertEquals(0L, draft.durationMs)
    }

    @Test
    fun `outgoing declined call is rejected by them`() {
        val b = CallRecordBuilder()
        b.onOutgoingStarted("peer-a", "Ayesha", t0)
        val draft = b.onFinished("Declined", t0 + 5_000)
        assertEquals(CallOutcome.REJECTED, draft!!.outcome)
        assertEquals("They declined", draft.reason)
    }

    @Test
    fun `outgoing cancelled while ringing is not a failure`() {
        val b = CallRecordBuilder()
        b.onOutgoingStarted("peer-a", "Ayesha", t0)
        val draft = b.onFinished("Call ended", t0 + 4_000)
        assertEquals(CallOutcome.MISSED, draft!!.outcome)
        assertEquals("Cancelled", draft.reason)
    }

    @Test
    fun `outgoing unreachable call fails with a plain reason`() {
        val b = CallRecordBuilder()
        b.onOutgoingStarted("peer-a", "Ayesha", t0)
        val draft = b.onFinished(
            "Peer not reachable on the direct link — they may be out of range",
            t0 + 45_000,
        )
        assertEquals(CallOutcome.FAILED, draft!!.outcome)
        assertEquals("Couldn't reach them — too far away", draft.reason)
    }

    @Test
    fun `incoming unanswered call is a missed call`() {
        val b = CallRecordBuilder()
        b.onIncomingStarted("peer-b", "Bilal", t0)
        val draft = b.onFinished("Connection lost", t0 + 40_000)
        assertEquals(CallOutcome.MISSED, draft!!.outcome)
        assertEquals(CallDirection.INCOMING, draft.direction)
        assertEquals("Missed call", draft.reason)
    }

    @Test
    fun `incoming call declined by me is rejected`() {
        val b = CallRecordBuilder()
        b.onIncomingStarted("peer-b", "Bilal", t0)
        b.onRejectedByMe()
        val draft = b.onFinished(null, t0 + 2_000)
        assertEquals(CallOutcome.REJECTED, draft!!.outcome)
        assertEquals("You declined", draft.reason)
    }

    @Test
    fun `incoming answered then lost keeps answered with the reason`() {
        val b = CallRecordBuilder()
        b.onIncomingStarted("peer-b", "Bilal", t0)
        b.onAnswered(t0 + 2_000)
        val draft = b.onFinished("Connection lost", t0 + 32_000)
        assertEquals(CallOutcome.ANSWERED, draft!!.outcome)
        assertEquals(30_000L, draft.durationMs)
        assertEquals("The connection was lost", draft.reason)
    }

    @Test
    fun `finish with no attempt in flight fabricates nothing`() {
        val b = CallRecordBuilder()
        assertNull(b.onFinished("Call ended", t0))
        // And a second finish after a real one is also empty.
        b.onOutgoingStarted("peer-a", "Ayesha", t0)
        assertTrue(b.onFinished("No answer", t0 + 1000) != null)
        assertNull(b.onFinished("No answer", t0 + 2000))
    }

    @Test
    fun `a second start while one is in flight is ignored`() {
        val b = CallRecordBuilder()
        b.onOutgoingStarted("peer-a", "Ayesha", t0)
        b.onIncomingStarted("peer-b", "Bilal", t0 + 10)
        assertEquals("peer-a", b.currentPeerId())
        val draft = b.onFinished("No answer", t0 + 1000)
        assertEquals("peer-a", draft!!.peerId)
    }

    @Test
    fun `plain reasons de-jargon the controller strings`() {
        assertEquals(
            "This phone can't make nearby calls",
            CallReasons.plainReason("Google Play services is not available on this device"),
        )
        assertEquals(
            "The microphone couldn't be opened",
            CallReasons.plainReason("Microphone unavailable on this device"),
        )
        assertNull(CallReasons.plainReason("Call ended"))
        assertNull(CallReasons.plainReason(null))
        assertTrue(CallReasons.isFailure("Could not reach the peer on the direct link"))
    }
}
