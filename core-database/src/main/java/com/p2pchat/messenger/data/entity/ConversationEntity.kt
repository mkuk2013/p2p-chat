package com.p2pchat.messenger.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per one-to-one conversation, keyed by the remote peer.
 *
 * The `lastMessage*` columns beyond the preview describe the latest
 * message so the chats list can render WhatsApp-style ticks truthfully:
 * [lastMessageMine] + [lastMessageState] drive the tick glyphs and
 * [lastMessageKind] switches the preview to the voice-note form. They are
 * kept in sync by ChatRepository whenever a message is sent, received, or
 * its delivery state changes — [lastMessageId] is the join key for that.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val peerId: String,
    val displayName: String,
    val lastMessagePreview: String = "",
    val lastMessageMs: Long = 0L,
    val unreadCount: Int = 0,
    val lastMessageId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val lastMessageMine: Boolean = false,
    val lastMessageState: String? = null,
    @ColumnInfo(defaultValue = "TEXT")
    val lastMessageKind: String = "TEXT"
)
