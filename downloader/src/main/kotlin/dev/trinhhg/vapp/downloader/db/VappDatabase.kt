package dev.trinhhg.vapp.downloader.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        RepositorySourceEntity::class,
        ExtensionPackageEntity::class,
        BookEntity::class,
        ChapterEntity::class,
        DownloadTaskEntity::class,
        BookmarkEntity::class,
        CleanupRuleEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class VappDatabase : RoomDatabase() {
    abstract fun repositorySourceDao(): RepositorySourceDao

    abstract fun extensionPackageDao(): ExtensionPackageDao

    abstract fun bookDao(): BookDao

    abstract fun chapterDao(): ChapterDao

    abstract fun downloadTaskDao(): DownloadTaskDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun cleanupRuleDao(): CleanupRuleDao

    companion object {
        private const val DB_SUBDIR = "vapp/db"
        private const val DB_FILE_NAME = "vapp.db"

        fun createPersistent(context: Context): VappDatabase {
            val dbDir = context.filesDir.resolve(DB_SUBDIR)
            if (!dbDir.exists()) {
                dbDir.mkdirs()
            }
            val dbFile = dbDir.resolve(DB_FILE_NAME)
            return Room
                .databaseBuilder(
                    context.applicationContext,
                    VappDatabase::class.java,
                    dbFile.absolutePath,
                ).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
        }

        fun createInMemory(
            context: Context,
            allowMainThread: Boolean = false,
        ): VappDatabase =
            Room
                .inMemoryDatabaseBuilder(context.applicationContext, VappDatabase::class.java)
                .apply {
                    if (allowMainThread) allowMainThreadQueries()
                }.build()
    }
}
