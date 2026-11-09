package tomate.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ProfileEntity::class,
        PostEntity::class,
        CommentEntity::class,
        StoryEntity::class,
        CommunityEntity::class,
        CommunityMemberCacheEntity::class,
        ConversationEntity::class,
        ConversationMemberEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        SyncStateEntity::class,
        BlockedUserEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TomateDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun postDao(): PostDao
    abstract fun commentDao(): CommentDao
    abstract fun storyDao(): StoryDao
    abstract fun communityDao(): CommunityDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun blockDao(): BlockDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val NAME = "tomate.db"

        /** v2 adds the stories table; existing posts, messages and pending work are preserved. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `stories` (
                        `id` TEXT NOT NULL,
                        `authorId` TEXT NOT NULL,
                        `authorHandle` TEXT NOT NULL,
                        `authorDisplayName` TEXT NOT NULL,
                        `body` TEXT NOT NULL,
                        `background` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `expiresAt` INTEGER NOT NULL,
                        `viewedByMe` INTEGER NOT NULL,
                        `pendingSync` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stories_authorId` ON `stories` (`authorId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stories_expiresAt` ON `stories` (`expiresAt`)")
            }
        }

        fun build(context: Context): TomateDatabase =
            Room.databaseBuilder(context.applicationContext, TomateDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
