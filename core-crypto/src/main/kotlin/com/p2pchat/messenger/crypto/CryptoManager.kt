package com.p2pchat.messenger.crypto

import com.p2pchat.messenger.core.codec.EnvelopeCodec
import com.p2pchat.messenger.core.model.MessageEnvelope
import com.p2pchat.messenger.core.model.PeerIds
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption built ONLY from reviewed primitives:
 *  - Ed25519 signatures (Bouncy Castle)
 *  - X25519 ephemeral-static key agreement (Bouncy Castle)
 *  - HKDF-SHA256 key derivation (Bouncy Castle), info="p2pchat-v1",
 *    salt = ephemeralPub || recipientPub
 *  - AES-256-GCM AEAD (JCA), 12-byte random nonce, 128-bit tag
 * No custom algorithms, no hardcoded secrets.
 *
 * Sealing is one-shot per message (fresh ephemeral keypair per message),
 * which gives per-message forward secrecy against later compromise of the
 * recipient's static X25519 key for that message's ephemeral, but this is
 * NOT the Signal double-ratchet — there is no session ratcheting. That
 * limitation is documented in the project README.
 */
class CryptoManager(private val random: SecureRandom = SecureRandom()) {

    class SealedPayload(
        val ephemeralPubKey: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SealedPayload) return false
            return ephemeralPubKey.contentEquals(other.ephemeralPubKey) &&
                nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext)
        }

        override fun hashCode(): Int {
            var r = ephemeralPubKey.contentHashCode()
            r = 31 * r + nonce.contentHashCode()
            r = 31 * r + ciphertext.contentHashCode()
            return r
        }
    }

    fun createIdentity(displayName: String): Identity {
        val edGen = Ed25519KeyPairGenerator()
        edGen.init(Ed25519KeyGenerationParameters(random))
        val edKp: AsymmetricCipherKeyPair = edGen.generateKeyPair()
        val edPub = (edKp.public as Ed25519PublicKeyParameters).encoded
        val edPriv = (edKp.private as Ed25519PrivateKeyParameters).encoded

        val xGen = X25519KeyPairGenerator()
        xGen.init(X25519KeyGenerationParameters(random))
        val xKp: AsymmetricCipherKeyPair = xGen.generateKeyPair()
        val xPub = (xKp.public as X25519PublicKeyParameters).encoded
        val xPriv = (xKp.private as X25519PrivateKeyParameters).encoded

        return Identity(
            peerId = PeerIds.fromPublicKey(edPub),
            displayName = displayName,
            ed25519Public = edPub,
            x25519Public = xPub,
            ed25519Private = edPriv,
            x25519Private = xPriv,
        )
    }

    // ---- Signatures ----

    fun sign(identity: Identity, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(identity.ed25519Private, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.isEmpty()) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (e: Exception) {
            false
        }
    }

    // ---- Sealed (encrypted) payloads ----

    fun seal(sender: Identity, recipientX25519Pub: ByteArray, plaintext: ByteArray): SealedPayload {
        // Fresh ephemeral X25519 keypair per message.
        val xGen = X25519KeyPairGenerator()
        xGen.init(X25519KeyGenerationParameters(random))
        val ephKp = xGen.generateKeyPair()
        val ephPub = (ephKp.public as X25519PublicKeyParameters).encoded
        val ephPriv = ephKp.private as X25519PrivateKeyParameters

        val shared = agree(ephPriv, recipientX25519Pub)
        val key = deriveKey(shared, salt = ephPub + recipientX25519Pub)
        val nonce = ByteArray(GCM_NONCE_BYTES).also { random.nextBytes(it) }
        val ciphertext = aesGcm(encrypt = true, key = key, nonce = nonce, input = plaintext)
        return SealedPayload(ephPub, nonce, ciphertext)
    }

    /**
     * @param senderX25519PubEphemeral the ephemeral public key carried with
     * the message (normally identical to payload.ephemeralPubKey; it is an
     * explicit parameter so the mesh layer can pass the envelope field).
     */
    fun open(
        recipient: Identity,
        senderX25519PubEphemeral: ByteArray,
        payload: SealedPayload,
    ): ByteArray {
        val ephPub = if (senderX25519PubEphemeral.isNotEmpty()) {
            senderX25519PubEphemeral
        } else {
            payload.ephemeralPubKey
        }
        val priv = X25519PrivateKeyParameters(recipient.x25519Private, 0)
        val shared = agree(priv, ephPub)
        val key = deriveKey(shared, salt = ephPub + recipient.x25519Public)
        return aesGcm(encrypt = false, key = key, nonce = payload.nonce, input = payload.ciphertext)
    }

    fun open(recipient: Identity, payload: SealedPayload): ByteArray =
        open(recipient, payload.ephemeralPubKey, payload)

    // ---- Envelope signing ----

    /** Bytes that are signed for an envelope: codec form with signature emptied. */
    fun envelopeSigningBytes(envelope: MessageEnvelope): ByteArray =
        EnvelopeCodec.serialize(envelope.copy(signature = ByteArray(0)))

    fun signEnvelope(identity: Identity, envelope: MessageEnvelope): MessageEnvelope {
        val sig = sign(identity, envelopeSigningBytes(envelope))
        return envelope.copy(signature = sig)
    }

    /**
     * @param pubKeyResolver maps senderId (PeerId) to that peer's Ed25519
     * public key, or null if the peer is unknown — unknown senders fail.
     */
    fun verifyEnvelope(
        pubKeyResolver: (String) -> ByteArray?,
        envelope: MessageEnvelope,
    ): Boolean {
        val pub = pubKeyResolver(envelope.senderId) ?: return false
        return verify(pub, envelopeSigningBytes(envelope), envelope.signature)
    }

    // ---- Internals ----

    private fun agree(priv: X25519PrivateKeyParameters, pubBytes: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(priv)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(pubBytes, 0), secret, 0)
        return secret
    }

    private fun deriveKey(ikm: ByteArray, salt: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, HKDF_INFO))
        val key = ByteArray(32)
        hkdf.generateBytes(key, 0, key.size)
        return key
    }

    private fun aesGcm(encrypt: Boolean, key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_BITS, nonce)
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            spec,
        )
        return cipher.doFinal(input)
    }

    companion object {
        val HKDF_INFO: ByteArray = "p2pchat-v1".toByteArray(Charsets.UTF_8)
        const val GCM_NONCE_BYTES: Int = 12
        const val GCM_TAG_BITS: Int = 128
    }
}
