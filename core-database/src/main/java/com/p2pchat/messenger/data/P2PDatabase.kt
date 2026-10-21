package com.p2pchat.messenger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.p2pchat.messenger.data.dao.ConversationDao
import com.p2pchat.messenger.data.dao.MessageDao
import com.p2pchat.messenger.data.dao.OutboxDao
import com.p2pchat.messenger.data.dao.PeerDao
import com.p2pchat.messenger.data.dao.SeenDao
import com.p2pchat.messenger.data.entity.ConversationEntity
import com.p2pchat.messenger.data.entity.MessageEntity
import com.p2pchat.messenger.data.entity.OutboxEntity
import com.p2pchat.messenger.data.entity.PeerEntity
import com.p2pchat.messenger.data.entity.SeenEntity

@Database(
    entities = [
        PeerEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        SeenEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class P2PDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun seenDao(): SeenDao

    companion object {
        const val DATABASE_NAME = "p2pchat.db"

        fun create(context: Context): P2PDatabase =
            Room.databaseBuilder(context.applicationContext, P2PDatabase::class.java, DATABASE_NAME)
                .build()
    }
}
