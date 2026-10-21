package com.p2pchat.messenger.crypto

/**
 * Persistence contract for [Identity]. The Android implementation lives in
 * the :app / :core-database layer and MUST wrap private key material with
 * a key from the Android Keystore before it touches disk. This module only
 * defines the interface plus an in-memory implementation for JVM tests.
 */
interface SecureKeyStorage {
    suspend fun save(identity: Identity)
    suspend fun load(): Identity?
    suspend fun clear()
}

/** Test-only storage. Never use in production — keys sit in plain memory. */
class InMemoryKeyStorage : SecureKeyStorage {
    private var stored: Identity? = null

    override suspend fun save(identity: Identity) {
        stored = Identity(
            peerId = identity.peerId,
            displayName = identity.displayName,
            ed25519Public = identity.ed25519Public.copyOf(),
            x25519Public = identity.x25519Public.copyOf(),
            ed25519Private = identity.ed25519Private.copyOf(),
            x25519Private = identity.x25519Private.copyOf(),
        )
    }

    override suspend fun load(): Identity? = stored

    override suspend fun clear() {
        stored = null
    }
}
