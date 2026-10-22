package com.p2pchat.messenger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.p2pchat.messenger.data.dao.CallRecordDao
import com.p2pchat.messenger.data.dao.ConversationDao
import com.p2pchat.messenger.data.dao.MessageDao
import com.p2pchat.messenger.data.dao.OutboxDao
import com.p2pchat.messenger.data.dao.PeerDao
import com.p2pchat.messenger.data.dao.SeenDao
import com.p2pchat.messenger.data.dao.VoiceChunkDao
import com.p2pchat.messenger.data.dao.VoiceTransferDao
import com.p2pchat.messenger.data.entity.CallRecordEntity
import com.p2pchat.messenger.data.entity.ConversationEntity
import com.p2pchat.messenger.data.entity.MessageEntity
import com.p2pchat.messenger.data.entity.OutboxEntity
import com.p2pchat.messenger.data.entity.PeerEntity
import com.p2pchat.messenger.data.entity.SeenEntity
import com.p2pchat.messenger.data.entity.VoiceChunkEntity
import com.p2pchat.messenger.data.entity.VoiceTransferEntity

@Database(
    entities = [
        PeerEntity::class,
        ConversationEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        SeenEntity::class,
        VoiceTransferEntity::class,
        VoiceChunkEntity::class,
        CallRecordEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class P2PDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun seenDao(): SeenDao
    abstract fun voiceTransferDao(): VoiceTransferDao
    abstract fun voiceChunkDao(): VoiceChunkDao
    abstract fun callRecordDao(): CallRecordDao

    companion object {
        const val DATABASE_NAME = "p2pchat.db"

        /**
         * v1 → v2 (WhatsApp-style release): messages gain kind/media
         * columns for voice notes, conversations gain last-message
         * tick metadata, and the two voice transfer tables are created.
         * The added-column defaults match the @ColumnInfo defaults on the
         * entities so Room's post-migration schema validation passes.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'TEXT'")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaPath TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN durationMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE messages ADD COLUMN blobId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN lastMessageId TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN lastMessageMine INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE conversations ADD COLUMN lastMessageState TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN lastMessageKind TEXT NOT NULL DEFAULT 'TEXT'")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voice_transfers` (" +
                        "`blobId` TEXT NOT NULL, `conversationPeerId` TEXT NOT NULL, " +
                        "`isMine` INTEGER NOT NULL, `totalChunks` INTEGER NOT NULL, " +
                        "`completedChunks` INTEGER NOT NULL, `totalBytes` INTEGER NOT NULL, " +
                        "`sha256Hex` TEXT NOT NULL, `durationMs` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, `filePath` TEXT, `createdAtMs` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`blobId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `voice_chunks` (" +
                        "`blobId` TEXT NOT NULL, `chunkIndex` INTEGER NOT NULL, " +
                        "`envelopeMessageId` TEXT, `payload` BLOB, `state` TEXT NOT NULL, " +
                        "PRIMARY KEY(`blobId`, `chunkIndex`))"
                )
            }
        }

        /**
         * v2 → v3 (v1.2.0 simplicity release): the call-history table
         * behind the Calls tab. Pure addition — no existing table is
         * touched, so all chats, voice notes and settings carry over.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `call_records` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`peerId` TEXT NOT NULL, `peerName` TEXT NOT NULL, " +
                        "`direction` TEXT NOT NULL, `outcome` TEXT NOT NULL, " +
                        "`reason` TEXT, `startedAtMs` INTEGER NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL)"
                )
            }
        }

        fun create(context: Context): P2PDatabase =
            Room.databaseBuilder(context.applicationContext, P2PDatabase::class.java, DATABASE_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
