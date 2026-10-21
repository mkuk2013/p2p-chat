package com.p2pchat.messenger.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.p2pchat.messenger.crypto.CryptoManager
import com.p2pchat.messenger.crypto.Identity
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persists the local [Identity] with its private keys encrypted at rest.
 *
 * A random AES-256-GCM key is generated inside the Android Keystore
 * (alias [KEY_ALIAS], never exportable). The identity is serialized to JSON
 * (keys Base64-encoded), encrypted with that key, and only the ciphertext
 * (IV prepended, Base64) is written to SharedPreferences. Private keys are
 * therefore never stored in plaintext on disk.
 *
 * Recovery limitation: if the Keystore key is lost (app data cleared,
 * device reset, some OEM keystore wipes) the stored identity is
 * unrecoverable and a new identity must be created — the peerId changes.
 */
class IdentityStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun exists(): Boolean = prefs.contains(KEY_IDENTITY_ENCRYPTED)

    fun save(identity: Identity) {
        val json = JSONObject().apply {
            put("peerId", identity.peerId)
            put("displayName", identity.displayName)
            put("ed25519Public", identity.ed25519Public.b64())
            put("x25519Public", identity.x25519Public.b64())
            put("ed25519Private", identity.ed25519Private.b64())
            put("x25519Private", identity.x25519Private.b64())
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val combined = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_IDENTITY_ENCRYPTED, combined.b64()).apply()
    }

    /**
     * Loads the stored identity, or null if none exists / cannot be decrypted.
     * [crypto] is part of the shared contract signature (callers pass the
     * CryptoManager that owns identity creation); reconstruction itself is a
     * plain data rebuild from the decrypted fields.
     */
    @Suppress("UNUSED_PARAMETER")
    fun load(crypto: CryptoManager): Identity? {
        val stored = prefs.getString(KEY_IDENTITY_ENCRYPTED, null) ?: return null
        return runCatching {
            val combined = Base64.decode(stored, Base64.NO_WRAP)
            require(combined.size > GCM_IV_BYTES) { "stored identity too short" }
            val iv = combined.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = combined.copyOfRange(GCM_IV_BYTES, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            Identity(
                peerId = json.getString("peerId"),
                displayName = json.getString("displayName"),
                ed25519Public = json.getString("ed25519Public").unb64(),
                x25519Public = json.getString("x25519Public").unb64(),
                ed25519Private = json.getString("ed25519Private").unb64(),
                x25519Private = json.getString("x25519Private").unb64()
            )
        }.getOrNull()
    }

    fun clear() {
        prefs.edit().remove(KEY_IDENTITY_ENCRYPTED).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.unb64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    companion object {
        const val PREFS_NAME = "identity_prefs"
        const val KEY_ALIAS = "p2pchat_identity_key"
        private const val KEY_IDENTITY_ENCRYPTED = "identity_encrypted_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
