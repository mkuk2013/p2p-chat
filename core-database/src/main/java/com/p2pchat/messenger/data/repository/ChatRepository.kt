package com.p2pchat.messenger.data.repository

import com.p2pchat.messenger.core.blob.BlobChunk
import com.p2pchat.messenger.core.blob.BlobChunkCodec
import com.p2pchat.messenger.core.blob.BlobReassembler
import com.p2pchat.messenger.core.model.EnvelopeType
import com.p2pchat.messenger.core.model.MessageEnvelope
import com.p2pchat.messenger.crypto.CryptoManager
import com.p2pchat.messenger.crypto.Identity
import com.p2pchat.messenger.data.P2PDatabase
import com.p2pchat.messenger.data.entity.ConversationEntity
import com.p2pchat.messenger.data.entity.MessageEntity
import com.p2pchat.messenger.data.entity.PeerEntity
import com.p2pchat.messenger.data.entity.VoiceChunkEntity
import com.p2pchat.messenger.data.entity.VoiceTransferEntity
import com.p2pchat.messenger.mesh.DeliveryState
import com.p2pchat.messenger.mesh.MeshRouter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/**
 * Conversations and messages for the local user.
 *
 * Sending: plaintext is sealed with [CryptoManager.seal] to the recipient's
 * X25519 public key, wrapped in a signed [MessageEnvelope], stored locally
 * as QUEUED (with its plaintext — this is the sender's own conversation),
 * and handed to the [MeshRouter]. The router / outbox own forwarding,
 * retries and ACK handling; this repository only reflects the truthful
 * [DeliveryState] the router reports back via [updateDeliveryState] and
 * [handleIncoming]. A message is marked DELIVERED only on a recipient ACK
 * (MeshEvent.AckReceived), never when a relay merely accepts it.
 *
 * Voice notes: a recording is split into sealed BLOB chunk envelopes
 * ([BlobChunkCodec]) that ride the same outbox/ACK machinery. The note is
 * DELIVERED only when EVERY chunk has been ACKed by the recipient device;
 * incoming chunks are stored in `voice_chunks` and the note appears in
 * the conversation only after reassembly + SHA-256 verification.
 */
class ChatRepository(
    private val db: P2PDatabase,
    private val crypto: CryptoManager,
    private val identity: Identity,
    private val router: MeshRouter? = null,
    private val voiceDir: File
) {
    /** Received/sent photos and files live next to the voice dir. */
    private val mediaDir: File by lazy {
        File(voiceDir.parentFile ?: voiceDir, "media")
    }

    val conversations: Flow<List<ConversationEntity>> = db.conversationDao().observeAll()

    fun searchConversations(query: String): Flow<List<ConversationEntity>> =
        if (query.isBlank()) conversations else db.conversationDao().search(query.trim())

    fun messages(peerId: String): Flow<List<MessageEntity>> =
        db.messageDao().observeByConversation(peerId)

    fun observeMessage(messageId: String): Flow<MessageEntity?> =
        db.messageDao().observeById(messageId)

    suspend fun getMessage(messageId: String): MessageEntity? = db.messageDao().getById(messageId)

    fun observeVoiceTransfer(blobId: String): Flow<VoiceTransferEntity?> =
        db.voiceTransferDao().observeById(blobId)

    /** All transfers (either direction) for one conversation — the chat
     * screen uses the incoming ACTIVE ones to show a "receiving" bubble. */
    fun observeVoiceTransfers(peerId: String): Flow<List<VoiceTransferEntity>> =
        db.voiceTransferDao().observeByConversation(peerId)

    // ------------------------------------------------------------ text

    /**
     * Seals, signs, persists (QUEUED) and dispatches a text message.
     * @throws IllegalStateException if the recipient peer (and its X25519
     * key) is not known yet — the UI must complete identity exchange first.
     */
    suspend fun sendText(peerId: String, text: String): MessageEntity {
        require(text.isNotBlank()) { "Message text must not be blank" }
        val peer = requireChatReadyPeer(peerId)

        val now = System.currentTimeMillis()
        val sealed = crypto.seal(identity, peer.x25519Public, text.toByteArray(Charsets.UTF_8))
        val unsigned = MessageEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = EnvelopeType.TEXT,
            senderId = identity.peerId,
            recipientId = peerId,
            timestampMs = now,
            expiryMs = now + MessageEnvelope.DEFAULT_EXPIRY_MS,
            ttl = MessageEnvelope.DEFAULT_TTL,
            hopCount = 0,
            ephemeralPubKey = sealed.ephemeralPubKey,
            nonce = sealed.nonce,
            ciphertext = sealed.ciphertext
        )
        val envelope = crypto.signEnvelope(identity, unsigned)

        val entity = MessageEntity(
            messageId = envelope.messageId,
            conversationPeerId = peerId,
            senderId = identity.peerId,
            recipientId = peerId,
            plaintext = text,
            timestampMs = now,
            expiryMs = envelope.expiryMs,
            deliveryState = DeliveryState.QUEUED.name,
            hopCount = 0,
            isMine = true
        )
        db.messageDao().insert(entity)
        upsertConversation(
            peerId = peerId,
            displayName = peer.displayName,
            preview = text,
            atMs = now,
            incrementUnread = false,
            messageId = entity.messageId,
            mine = true,
            state = DeliveryState.QUEUED.name,
            kind = MessageEntity.KIND_TEXT
        )
        dispatch(envelope)
        return entity
    }

    /** Called by the app layer for MeshEvent.MessageReceived addressed to us. */
    suspend fun handleIncoming(envelope: MessageEnvelope, plaintext: String) {
        if (db.seenDao().exists(envelope.messageId)) return
        db.seenDao().insert(
            com.p2pchat.messenger.data.entity.SeenEntity(envelope.messageId, System.currentTimeMillis())
        )
        db.messageDao().insert(
            MessageEntity(
                messageId = envelope.messageId,
                conversationPeerId = envelope.senderId,
                senderId = envelope.senderId,
                recipientId = envelope.recipientId,
                plaintext = plaintext,
                timestampMs = envelope.timestampMs,
                expiryMs = envelope.expiryMs,
                deliveryState = DeliveryState.DELIVERED.name,
                hopCount = envelope.hopCount,
                isMine = false
            )
        )
        val peer = db.peerDao().getById(envelope.senderId)
        upsertConversation(
            peerId = envelope.senderId,
            displayName = peer?.displayName ?: envelope.senderId.take(8),
            preview = plaintext,
            atMs = envelope.timestampMs,
            incrementUnread = true,
            messageId = envelope.messageId,
            mine = false,
            state = DeliveryState.DELIVERED.name,
            kind = MessageEntity.KIND_TEXT
        )
    }

    // ------------------------------------------------------------ voice notes

    /**
     * Queue a recorded voice note for [peerId]. Takes ownership of
     * [recordedFile]: it is moved into the voice store as the note's
     * local file (playback + retry source), split into sealed chunks and
     * dispatched. The note's messageId IS its blobId.
     * @throws IllegalStateException if the peer's keys are unknown, or
     * IllegalArgumentException if the recording exceeds the blob cap.
     */
    suspend fun sendVoiceNote(peerId: String, recordedFile: File, durationMs: Int): MessageEntity {
        val peer = requireChatReadyPeer(peerId)
        val bytes = recordedFile.readBytes()
        require(bytes.size in 1..BlobChunkCodec.MAX_BLOB_BYTES) {
            "Voice note is ${bytes.size} bytes — the mesh limit is " +
                "${BlobChunkCodec.MAX_BLOB_BYTES} bytes."
        }

        val blobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        voiceDir.mkdirs()
        val stored = File(voiceDir, "$blobId.m4a")
        if (recordedFile.absolutePath != stored.absolutePath) {
            recordedFile.copyTo(stored, overwrite = true)
            recordedFile.delete()
        }

        val frames = BlobChunkCodec.split(blobId, bytes, durationMs, VOICE_MIME)
        db.voiceTransferDao().upsert(
            VoiceTransferEntity(
                blobId = blobId,
                conversationPeerId = peerId,
                isMine = true,
                totalChunks = frames.size,
                completedChunks = 0,
                totalBytes = bytes.size.toLong(),
                sha256Hex = BlobChunkCodec.sha256Hex(bytes),
                durationMs = durationMs,
                state = VoiceTransferEntity.STATE_ACTIVE,
                filePath = stored.absolutePath,
                createdAtMs = now
            )
        )
        val entity = MessageEntity(
            messageId = blobId,
            conversationPeerId = peerId,
            senderId = identity.peerId,
            recipientId = peerId,
            plaintext = "",
            timestampMs = now,
            expiryMs = now + MessageEnvelope.DEFAULT_EXPIRY_MS,
            deliveryState = DeliveryState.QUEUED.name,
            hopCount = 0,
            isMine = true,
            kind = MessageEntity.KIND_VOICE,
            mediaPath = stored.absolutePath,
            durationMs = durationMs,
            blobId = blobId
        )
        db.messageDao().insert(entity)
        upsertConversation(
            peerId = peerId,
            displayName = peer.displayName,
            preview = MessageEntity.VOICE_PREVIEW,
            atMs = now,
            incrementUnread = false,
            messageId = blobId,
            mine = true,
            state = DeliveryState.QUEUED.name,
            kind = MessageEntity.KIND_VOICE
        )
        frames.forEachIndexed { index, frame ->
            val envelope = sealChunkEnvelope(peer, frame, now)
            db.voiceChunkDao().upsert(
                VoiceChunkEntity(
                    blobId = blobId,
                    chunkIndex = index,
                    envelopeMessageId = envelope.messageId,
                    payload = null,
                    state = DeliveryState.QUEUED.name
                )
            )
            dispatch(envelope)
        }
        return entity
    }

    /**
     * Send a photo or a file (v1.4.0) through the same chunked blob
     * machinery as voice notes: the bytes ride sealed chunk envelopes,
     * and the message reaches DELIVERED only when every chunk has been
     * ACKed by the recipient. The message kind is derived from [mime]
     * on BOTH sides (image mime → photo bubble, anything else → file
     * card), so sender and receiver always render the same thing.
     * [name] is the original file name, carried in every BLB2 frame.
     */
    suspend fun sendMedia(
        peerId: String,
        bytes: ByteArray,
        mime: String,
        name: String
    ): MessageEntity {
        val peer = requireChatReadyPeer(peerId)
        require(bytes.size in 1..BlobChunkCodec.MAX_BLOB_BYTES) {
            "File is ${bytes.size} bytes — the mesh limit is " +
                "${BlobChunkCodec.MAX_BLOB_BYTES} bytes."
        }
        val blobId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        mediaDir.mkdirs()
        val stored = File(mediaDir, "$blobId.${mediaExtension(name, mime)}")
        stored.writeBytes(bytes)

        val kind = kindForMime(mime)
        val frames = BlobChunkCodec.split(blobId, bytes, 0, mime, name = name)
        db.voiceTransferDao().upsert(
            VoiceTransferEntity(
                blobId = blobId,
                conversationPeerId = peerId,
                isMine = true,
                totalChunks = frames.size,
                completedChunks = 0,
                totalBytes = bytes.size.toLong(),
                sha256Hex = BlobChunkCodec.sha256Hex(bytes),
                durationMs = 0,
                state = VoiceTransferEntity.STATE_ACTIVE,
                filePath = stored.absolutePath,
                createdAtMs = now
            )
        )
        val entity = MessageEntity(
            messageId = blobId,
            conversationPeerId = peerId,
            senderId = identity.peerId,
            recipientId = peerId,
            plaintext = name,
            timestampMs = now,
            expiryMs = now + MessageEnvelope.DEFAULT_EXPIRY_MS,
            deliveryState = DeliveryState.QUEUED.name,
            hopCount = 0,
            isMine = true,
            kind = kind,
            mediaPath = stored.absolutePath,
            durationMs = 0,
            blobId = blobId
        )
        db.messageDao().insert(entity)
        upsertConversation(
            peerId = peerId,
            displayName = peer.displayName,
            preview = if (kind == MessageEntity.KIND_IMAGE) {
                MessageEntity.IMAGE_PREVIEW
            } else {
                MessageEntity.filePreview(name)
            },
            atMs = now,
            incrementUnread = false,
            messageId = blobId,
            mine = true,
            state = DeliveryState.QUEUED.name,
            kind = kind
        )
        frames.forEachIndexed { index, frame ->
            val envelope = sealChunkEnvelope(peer, frame, now)
            db.voiceChunkDao().upsert(
                VoiceChunkEntity(
                    blobId = blobId,
                    chunkIndex = index,
                    envelopeMessageId = envelope.messageId,
                    payload = null,
                    state = DeliveryState.QUEUED.name
                )
            )
            dispatch(envelope)
        }
        return entity
    }

    /**
     * One decrypted BLOB chunk arrived (the router already verified,
     * decrypted and ACKed it). Stores the chunk; when the last missing
     * chunk lands, reassembles, verifies SHA-256, writes the media
     * file and inserts the message into the sender's conversation.
     * Partial or corrupt blobs never become messages.
     */
    suspend fun handleIncomingBlobChunk(envelope: MessageEnvelope, frameBytes: ByteArray) {
        val chunk = try {
            BlobChunkCodec.decode(frameBytes)
        } catch (e: Exception) {
            return // malformed frame — the router ACKed receipt of the envelope, but nothing is stored
        }
        if (db.seenDao().exists(envelope.messageId)) return
        db.seenDao().insert(
            com.p2pchat.messenger.data.entity.SeenEntity(envelope.messageId, System.currentTimeMillis())
        )
        val senderId = envelope.senderId
        val shaHex = chunk.sha256.toHex()
        var transfer = db.voiceTransferDao().getById(chunk.blobId)
        if (transfer == null) {
            transfer = VoiceTransferEntity(
                blobId = chunk.blobId,
                conversationPeerId = senderId,
                isMine = false,
                totalChunks = chunk.total,
                completedChunks = 0,
                totalBytes = chunk.totalBytes,
                sha256Hex = shaHex,
                durationMs = chunk.durationMs,
                state = VoiceTransferEntity.STATE_ACTIVE,
                filePath = null,
                createdAtMs = envelope.timestampMs
            )
            db.voiceTransferDao().upsert(transfer)
        } else {
            // A frame that disagrees with the announced blob metadata
            // must not join this transfer (same rule as BlobReassembler).
            if (transfer.totalChunks != chunk.total ||
                transfer.totalBytes != chunk.totalBytes ||
                transfer.sha256Hex != shaHex ||
                transfer.state == VoiceTransferEntity.STATE_COMPLETE
            ) {
                return
            }
        }

        db.voiceChunkDao().insertIgnore(
            VoiceChunkEntity(
                blobId = chunk.blobId,
                chunkIndex = chunk.index,
                envelopeMessageId = envelope.messageId,
                payload = chunk.payload,
                state = VoiceChunkEntity.STATE_RECEIVED
            )
        )
        val stored = db.voiceChunkDao().getAllForBlob(chunk.blobId)
        if (stored.size < chunk.total) {
            db.voiceTransferDao().updateProgress(
                chunk.blobId, stored.size, VoiceTransferEntity.STATE_ACTIVE
            )
            return
        }

        // All chunks present — verify before anything becomes a message.
        val reassembler = BlobReassembler()
        for (row in stored) {
            val payload = row.payload
            if (payload == null) {
                db.voiceTransferDao().updateProgress(
                    chunk.blobId, stored.size, VoiceTransferEntity.STATE_FAILED
                )
                return
            }
            reassembler.add(
                BlobChunk(
                    blobId = chunk.blobId,
                    index = row.chunkIndex,
                    total = chunk.total,
                    totalBytes = chunk.totalBytes,
                    sha256 = chunk.sha256,
                    durationMs = chunk.durationMs,
                    mime = chunk.mime,
                    payload = payload
                )
            )
        }
        val assembled = reassembler.assemble()
        if (assembled == null) {
            db.voiceTransferDao().updateProgress(
                chunk.blobId, stored.size, VoiceTransferEntity.STATE_FAILED
            )
            return
        }

        // One completion path for every blob kind (v1.4.0): the kind
        // is derived from the mime announced in the frames, exactly as
        // the sender derived it — voice keeps its .m4a home, photos
        // and files land in the media dir under their own extension.
        val kind = kindForMime(chunk.mime)
        val isVoice = kind == MessageEntity.KIND_VOICE
        val dir = if (isVoice) voiceDir else mediaDir
        dir.mkdirs()
        val ext = if (isVoice) "m4a" else mediaExtension(chunk.name, chunk.mime)
        val outFile = File(dir, "${chunk.blobId}.$ext")
        outFile.writeBytes(assembled)
        val displayName = when {
            isVoice -> ""
            chunk.name.isNotEmpty() -> chunk.name
            kind == MessageEntity.KIND_IMAGE -> "photo.$ext"
            else -> "file.$ext"
        }
        if (db.messageDao().getById(chunk.blobId) == null) {
            db.messageDao().insert(
                MessageEntity(
                    messageId = chunk.blobId,
                    conversationPeerId = senderId,
                    senderId = senderId,
                    recipientId = identity.peerId,
                    plaintext = displayName,
                    timestampMs = envelope.timestampMs,
                    expiryMs = envelope.expiryMs,
                    deliveryState = DeliveryState.DELIVERED.name,
                    hopCount = envelope.hopCount,
                    isMine = false,
                    kind = kind,
                    mediaPath = outFile.absolutePath,
                    durationMs = if (isVoice) chunk.durationMs else 0,
                    blobId = chunk.blobId
                )
            )
            val peer = db.peerDao().getById(senderId)
            upsertConversation(
                peerId = senderId,
                displayName = peer?.displayName ?: senderId.take(8),
                preview = when (kind) {
                    MessageEntity.KIND_VOICE -> MessageEntity.VOICE_PREVIEW
                    MessageEntity.KIND_IMAGE -> MessageEntity.IMAGE_PREVIEW
                    else -> MessageEntity.filePreview(displayName)
                },
                atMs = envelope.timestampMs,
                incrementUnread = true,
                messageId = chunk.blobId,
                mine = false,
                state = DeliveryState.DELIVERED.name,
                kind = kind
            )
        }
        db.voiceTransferDao().updateProgress(
            chunk.blobId, chunk.total, VoiceTransferEntity.STATE_COMPLETE
        )
        db.voiceTransferDao().updateStateAndFile(
            chunk.blobId, VoiceTransferEntity.STATE_COMPLETE, outFile.absolutePath
        )
        // Chunk payloads are no longer needed once the file exists.
        db.voiceChunkDao().deleteForBlob(chunk.blobId)
    }

    /**
     * Router delivery event for one envelope ID. If the ID belongs to an
     * outgoing voice chunk, fold it into the note's progress; unknown IDs
     * (text messages, relay traffic) are ignored here — text is handled by
     * [updateDeliveryState].
     */
    suspend fun onChunkDeliveryEvent(envelopeMessageId: String, state: DeliveryState) {
        val chunk = db.voiceChunkDao().getByEnvelopeId(envelopeMessageId) ?: return
        if (chunk.state == DeliveryState.DELIVERED.name) return // never downgrade
        db.voiceChunkDao().updateState(chunk.blobId, chunk.chunkIndex, state.name)
        refreshOutgoingVoiceProgress(chunk.blobId)
    }

    /** Re-queue only the chunks of [blobId] that were never ACKed. The
     * receiver dedups by (blobId, index), so re-sent chunks are harmless. */
    suspend fun retryVoiceNote(blobId: String) {
        val transfer = db.voiceTransferDao().getById(blobId) ?: return
        if (!transfer.isMine) return
        val file = transfer.filePath?.let { File(it) }
        if (file == null || !file.exists()) {
            db.voiceTransferDao().updateProgress(
                blobId, transfer.completedChunks, VoiceTransferEntity.STATE_FAILED
            )
            return
        }
        val peer = db.peerDao().getById(transfer.conversationPeerId) ?: return
        if (peer.x25519Public.isEmpty()) return
        val bytes = file.readBytes()
        // Media blobs (v1.4.0) re-split with their own mime + name, so a
        // retried photo/file still arrives as the same kind with its
        // file name intact; voice notes keep the original frame shape.
        val message = db.messageDao().getById(blobId)
        val (retryMime, retryName) = when (message?.kind) {
            MessageEntity.KIND_IMAGE, MessageEntity.KIND_FILE ->
                mimeForExtension(file.extension) to message.plaintext
            else -> VOICE_MIME to ""
        }
        val frames = BlobChunkCodec.split(
            blobId, bytes, transfer.durationMs, retryMime, name = retryName
        )
        val now = System.currentTimeMillis()
        db.voiceChunkDao().getAllForBlob(blobId)
            .filter { it.state != DeliveryState.DELIVERED.name }
            .forEach { row ->
                val envelope = sealChunkEnvelope(peer, frames[row.chunkIndex], now)
                db.voiceChunkDao().updateStateAndEnvelope(
                    blobId, row.chunkIndex, DeliveryState.QUEUED.name, envelope.messageId
                )
                dispatch(envelope)
            }
        db.voiceTransferDao().updateProgress(
            blobId, transfer.completedChunks, VoiceTransferEntity.STATE_ACTIVE
        )
        updateDeliveryState(blobId, DeliveryState.QUEUED)
    }

    private suspend fun refreshOutgoingVoiceProgress(blobId: String) {
        val transfer = db.voiceTransferDao().getById(blobId) ?: return
        if (!transfer.isMine) return
        val chunks = db.voiceChunkDao().getAllForBlob(blobId)
        val delivered = chunks.count { it.state == DeliveryState.DELIVERED.name }
        val failed = chunks.any {
            it.state == DeliveryState.EXPIRED.name || it.state == DeliveryState.FAILED.name
        }
        val anyAccepted = chunks.any {
            it.state == DeliveryState.RELAYED.name || it.state == DeliveryState.DELIVERED.name
        }
        val message = db.messageDao().getById(blobId)
        when {
            delivered >= transfer.totalChunks -> {
                db.voiceTransferDao().updateProgress(
                    blobId, delivered, VoiceTransferEntity.STATE_COMPLETE
                )
                if (message != null && message.deliveryState != DeliveryState.DELIVERED.name) {
                    updateDeliveryState(blobId, DeliveryState.DELIVERED)
                }
            }
            failed -> {
                db.voiceTransferDao().updateProgress(
                    blobId, delivered, VoiceTransferEntity.STATE_FAILED
                )
                if (message != null && message.deliveryState != DeliveryState.FAILED.name) {
                    updateDeliveryState(blobId, DeliveryState.FAILED)
                }
            }
            else -> {
                db.voiceTransferDao().updateProgress(
                    blobId, delivered, VoiceTransferEntity.STATE_ACTIVE
                )
                if (message != null &&
                    message.deliveryState == DeliveryState.QUEUED.name && anyAccepted
                ) {
                    updateDeliveryState(blobId, DeliveryState.RELAYED)
                }
            }
        }
    }

    private fun sealChunkEnvelope(peer: PeerEntity, frame: ByteArray, now: Long): MessageEnvelope {
        val sealed = crypto.seal(identity, peer.x25519Public, frame)
        val unsigned = MessageEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = EnvelopeType.BLOB,
            senderId = identity.peerId,
            recipientId = peer.peerId,
            timestampMs = now,
            expiryMs = now + MessageEnvelope.DEFAULT_EXPIRY_MS,
            ttl = MessageEnvelope.DEFAULT_TTL,
            hopCount = 0,
            ephemeralPubKey = sealed.ephemeralPubKey,
            nonce = sealed.nonce,
            ciphertext = sealed.ciphertext
        )
        return crypto.signEnvelope(identity, unsigned)
    }

    private suspend fun requireChatReadyPeer(peerId: String): PeerEntity {
        val peer = db.peerDao().getById(peerId)
            ?: throw IllegalStateException(
                "Unknown peer $peerId — discover the peer and exchange identity keys before chatting."
            )
        require(peer.x25519Public.isNotEmpty()) {
            "Peer $peerId has no X25519 public key yet — identity exchange has not completed."
        }
        return peer
    }

    // ------------------------------------------------------------ shared

    private suspend fun dispatch(envelope: MessageEnvelope) {
        // Router queues into the OutboxStore itself; if no router is wired
        // (e.g. relay service stopped), the caller can enqueue via
        // RoomOutboxStore directly — the message stays QUEUED either way.
        router?.sendEnvelope(envelope)
    }

    /** Called by the app layer for MeshEvent.AckReceived / Forwarded / Expired. */
    suspend fun updateDeliveryState(messageId: String, state: DeliveryState, hopCount: Int? = null) {
        if (hopCount != null) {
            db.messageDao().updateDeliveryStateAndHops(messageId, state.name, hopCount)
        } else {
            db.messageDao().updateDeliveryState(messageId, state.name)
        }
        // Keep the chats-list ticks truthful for the latest message.
        val message = db.messageDao().getById(messageId)
        if (message != null) {
            db.conversationDao().updateLastMessageState(
                message.conversationPeerId, messageId, state.name
            )
        }
    }

    suspend fun deleteMessage(messageId: String) {
        val message = db.messageDao().getById(messageId)
        db.messageDao().deleteById(messageId)
        db.outboxDao().deleteById(messageId)
        val blobId = message?.blobId
        if (blobId != null) {
            // Remove the note's chunk envelopes from the outbox too, plus
            // its transfer bookkeeping and local file.
            db.voiceChunkDao().getAllForBlob(blobId).forEach { chunk ->
                chunk.envelopeMessageId?.let { db.outboxDao().deleteById(it) }
            }
            db.voiceChunkDao().deleteForBlob(blobId)
            db.voiceTransferDao().deleteById(blobId)
            message.mediaPath?.let { path -> runCatching { File(path).delete() } }
        }
    }

    suspend fun clearConversation(peerId: String) {
        db.voiceTransferDao().observeByConversation(peerId).first().forEach { transfer ->
            db.voiceChunkDao().deleteForBlob(transfer.blobId)
            transfer.filePath?.let { path -> runCatching { File(path).delete() } }
            db.voiceTransferDao().deleteById(transfer.blobId)
        }
        db.messageDao().deleteByConversation(peerId)
        db.conversationDao().deleteById(peerId)
    }

    suspend fun markConversationRead(peerId: String) = db.conversationDao().clearUnread(peerId)

    suspend fun ensureConversation(peerId: String, displayName: String) {
        if (db.conversationDao().getById(peerId) == null) {
            db.conversationDao().upsert(
                ConversationEntity(peerId = peerId, displayName = displayName)
            )
        }
    }

    private suspend fun upsertConversation(
        peerId: String,
        displayName: String,
        preview: String,
        atMs: Long,
        incrementUnread: Boolean,
        messageId: String,
        mine: Boolean,
        state: String,
        kind: String
    ) {
        val existing = db.conversationDao().getById(peerId)
        // A late-arriving older message must not rewind the list preview.
        if (existing != null && existing.lastMessageMs > atMs) return
        db.conversationDao().upsert(
            ConversationEntity(
                peerId = peerId,
                displayName = displayName,
                lastMessagePreview = preview.take(120),
                lastMessageMs = atMs,
                unreadCount = (existing?.unreadCount ?: 0) + if (incrementUnread) 1 else 0,
                lastMessageId = messageId,
                lastMessageMine = mine,
                lastMessageState = state,
                lastMessageKind = kind
            )
        )
    }

    companion object {
        const val VOICE_MIME: String = "audio/mp4"

        /** The single kind rule both sides apply (v1.4.0). */
        fun kindForMime(mime: String): String = when {
            mime == VOICE_MIME -> MessageEntity.KIND_VOICE
            mime.startsWith("image/") -> MessageEntity.KIND_IMAGE
            else -> MessageEntity.KIND_FILE
        }

        /** Safe file extension for a stored blob: from the original
         *  name when usable, else from the mime, else "bin". */
        fun mediaExtension(name: String, mime: String): String {
            val fromName = name.substringAfterLast('.', "")
                .lowercase()
                .filter { it.isLetterOrDigit() }
                .take(8)
            if (fromName.isNotEmpty()) return fromName
            return when (mime.lowercase()) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "application/pdf" -> "pdf"
                "text/plain" -> "txt"
                "audio/mpeg" -> "mp3"
                "video/mp4" -> "mp4"
                "application/zip" -> "zip"
                else -> "bin"
            }
        }

        /** Mime for a stored file's extension (blob retries). */
        fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "mp3" -> "audio/mpeg"
            "mp4" -> "video/mp4"
            "zip" -> "application/zip"
            "m4a" -> VOICE_MIME
            else -> "application/octet-stream"
        }
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }
