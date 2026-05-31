package com.aibyjohannes.alfred.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        ChatMessageEntity::class,
        WorkspaceEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun workspaceDao(): WorkspaceDao

    companion object {
        private const val DATABASE_NAME = "alfred.db"

        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create workspaces table
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `workspaces` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, " +
                    "`createdAtEpochMs` INTEGER NOT NULL, " +
                    "`isActive` INTEGER NOT NULL DEFAULT 0)"
                )
                
                // 2. Insert default "Personal" workspace
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT INTO `workspaces` (`id`, `name`, `createdAtEpochMs`, `isActive`) " +
                    "VALUES (1, 'Personal', $now, 1)"
                )
                
                // 3. Create temporary conversations table with the new schema (with FOREIGN KEY constraint)
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `conversations_new` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT, " +
                    "`createdAtEpochMs` INTEGER NOT NULL, " +
                    "`updatedAtEpochMs` INTEGER NOT NULL, " +
                    "`isActive` INTEGER NOT NULL, " +
                    "`workspaceId` INTEGER NOT NULL DEFAULT 1, " +
                    "FOREIGN KEY(`workspaceId`) REFERENCES `workspaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                
                // 4. Copy data from old conversations table to conversations_new
                // Since old conversations doesn't have workspaceId, we default it to 1
                db.execSQL(
                    "INSERT INTO `conversations_new` (`id`, `title`, `createdAtEpochMs`, `updatedAtEpochMs`, `isActive`, `workspaceId`) " +
                    "SELECT `id`, `title`, `createdAtEpochMs`, `updatedAtEpochMs`, `isActive`, 1 FROM `conversations`"
                )
                
                // 5. Drop old table
                db.execSQL("DROP TABLE `conversations`")
                
                // 6. Rename conversations_new to conversations
                db.execSQL("ALTER TABLE `conversations_new` RENAME TO `conversations`")
                
                // 7. Create index on workspaceId
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_conversations_workspaceId` ON `conversations` (`workspaceId`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(MIGRATION_1_2)
                .build().also { instance = it }
            }
        }
    }
}
