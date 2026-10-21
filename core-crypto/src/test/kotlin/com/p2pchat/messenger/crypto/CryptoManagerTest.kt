package com.p2pchat.messenger.crypto

import com.p2pchat.messenger.core.model.EnvelopeType
import com.p2pchat.messenger.core.model.MessageEnvelope
import com.p2pchat.messenger.core.model.PeerIds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CryptoManagerTest {

    private val crypto = CryptoManager()

    @Test
    fun identityCreationIsConsistent() {
        val alice = crypto.createIdentity("Alice")
        assertEquals(PeerIds.fromPublicKey(alice.ed25519Public), alice.peerId)
        assertTrue(PeerIds.isValid(alice.peerId))
        assertEquals(32, alice.ed25519Public.size)
        assertEquals(32, alice.x25519Public.size)
        val bob = crypto.createIdentity("Bob")
        assertFalse(alice.peerId == bob.peerId)
    }

    @Test
    fun sealOpenRoundtrip() {
        val alice = crypto.createIdentity("Alice")
        val bob = crypto.createIdentity("Bob")
        val plaintext = "Assalam-o-Alaikum, offline mesh!".toByteArray()
        val sealed = crypto.seal(alice, bob.x25519Public, plaintext)
        val opened = crypto.open(bob, sealed.ephemeralPubKey, sealed)
        assertArrayEquals(plaintext, opened)
        // Convenience overload
        assertArrayEquals(plaintext, crypto.open(bob, sealed))
    }

    @Test
    fun wrongRecipientFails() {
        val alice = crypto.createIdentity("Alice")
        val bob = crypto.createIdentity("Bob")
        val mallory = crypto.createIdentity("Mallory")
        val sealed = crypto.seal(alice, bob.x25519Public, "secret".toByteArray())
        try {
            crypto.open(mallory, sealed.ephemeralPubKey, sealed)
            fail("Wrong recipient must not decrypt")
        } catch (expected: Exception) {
            // AEAD tag verification failure — expected
        }
    }

    @Test
    fun tamperedCiphertextFails() {
        val alice = crypto.createIdentity("Alice")
        val bob = crypto.createIdentity("Bob")
        val sealed = crypto.seal(alice, bob.x25519Public, "secret".toByteArray())
        val tampered = CryptoManager.SealedPayload(
            sealed.ephemeralPubKey,
            sealed.nonce,
            sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
        )
        try {
            crypto.open(bob, tampered.ephemeralPubKey, tampered)
            fail("Tampered ciphertext must fail AEAD verification")
        } catch (expected: Exception) {
            // expected
        }
    }

    @Test
    fun signAndVerify() {
        val alice = crypto.createIdentity("Alice")
        val data = "envelope bytes".toByteArray()
        val sig = crypto.sign(alice, data)
        assertEquals(64, sig.size)
        assertTrue(crypto.verify(alice.ed25519Public, data, sig))
        assertFalse(crypto.verify(alice.ed25519Public, "other".toByteArray(), sig))
        val bob = crypto.createIdentity("Bob")
        assertFalse(crypto.verify(bob.ed25519Public, data, sig))
        assertFalse(crypto.verify(alice.ed25519Public, data, ByteArray(0)))
    }

    @Test
    fun envelopeSignAndVerify() {
        val alice = crypto.createIdentity("Alice")
        val bob = crypto.createIdentity("Bob")
        val plaintext = "hi bob".toByteArray()
        val sealed = crypto.seal(alice, bob.x25519Public, plaintext)
        val unsigned = MessageEnvelope(
            messageId = "env-1",
            type = EnvelopeType.TEXT,
            senderId = alice.peerId,
            recipientId = bob.peerId,
            timestampMs = 1_700_000_000_000L,
            expiryMs = 1_700_000_000_000L + 86_400_000L,
            ttl = 7,
            ephemeralPubKey = sealed.ephemeralPubKey,
            nonce = sealed.nonce,
            ciphertext = sealed.ciphertext,
        )
        val signed = crypto.signEnvelope(alice, unsigned)
        assertEquals(64, signed.signature.size)

        val resolver: (String) -> ByteArray? = { id ->
            when (id) {
                alice.peerId -> alice.ed25519Public
                bob.peerId -> bob.ed25519Public
                else -> null
            }
        }
        assertTrue(crypto.verifyEnvelope(resolver, signed))
        // Unknown sender fails
        assertFalse(crypto.verifyEnvelope({ null }, signed))
        // Tampering with ciphertext after signing fails
        val tampered = signed.copy(
            ciphertext = signed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
        )
        assertFalse(crypto.verifyEnvelope(resolver, tampered))
        // And Bob can still open the genuine envelope
        val payload = CryptoManager.SealedPayload(
            signed.ephemeralPubKey, signed.nonce, signed.ciphertext,
        )
        assertArrayEquals(plaintext, crypto.open(bob, payload))
    }

    @Test
    fun replayGuardRejectsStaleAndDuplicate() {
        val window = NonceWindow(windowMs = 60_000L)
        val now = 1_700_000_000_000L
        assertTrue(window.checkAndMark("n1", timestampMs = now, nowMs = now))
        assertFalse(window.checkAndMark("n1", timestampMs = now, nowMs = now + 1_000L)) // replay
        assertFalse(window.checkAndMark("n2", timestampMs = now - 120_000L, nowMs = now)) // stale
        assertTrue(isTimestampFresh(now, now))
        assertFalse(isTimestampFresh(now - 10 * 60_000L, now))
    }

    @Test
    fun inMemoryStorageRoundtrip() = runTest {
        val storage = InMemoryKeyStorage()
        assertNull(storage.load())
        val alice = crypto.createIdentity("Alice")
        storage.save(alice)
        val loaded = storage.load()
        assertNotNull(loaded)
        assertEquals(alice, loaded)
        storage.clear()
        assertNull(storage.load())
    }
}
