package com.p2pchat.messenger.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row per one-to-one conversation, keyed by the remote peer. */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val peerId: String,
    val displayName: String,
    val lastMessagePreview: String = "",
    val lastMessageMs: Long = 0L,
    val unreadCount: Int = 0
)
