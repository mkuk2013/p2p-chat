package com.p2pchat.messenger.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Duplicate-suppression cache: message ids this node has already processed. */
@Entity(tableName = "seen_messages")
data class SeenEntity(
    @PrimaryKey val messageId: String,
    val seenAtMs: Long
)
