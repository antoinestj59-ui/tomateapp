package tomate.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ProfileEntity::class,
        PostEntity::class,
        CommentEntity::class,
        CommunityEntity::class,
        CommunityMemberCacheEntity::class,
        ConversationEntity::class,
        ConversationMemberEntity::class,
        MessageEntity::class,
        OutboxEntity::class,
        SyncStateEntity::class,
        BlockedUserEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class TomateDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun postDao(): PostDao
    abstract fun commentDao(): CommentDao
    abstract fun communityDao(): CommunityDao
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun blockDao(): BlockDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val NAME = "tomate.db"

        fun build(context: Context): TomateDatabase =
            Room.databaseBuilder(context.applicationContext, TomateDatabase::class.java, NAME)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
