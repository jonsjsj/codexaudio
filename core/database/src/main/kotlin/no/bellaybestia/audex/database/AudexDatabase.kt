package no.bellaybestia.audex.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [
        ServerEntity::class,
        RemoteItemEntity::class,
        RemoteChapterEntity::class,
        AuthorEntity::class,
        SeriesEntity::class,
        WorkEntity::class,
        EditionEntity::class,
        OverrideEntity::class,
        ProgressEntity::class,
        PendingSessionEntity::class,
        PendingEbookProgressEntity::class,
        DownloadEntity::class,
        HighlightEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AudexDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun remoteItemDao(): RemoteItemDao
    abstract fun chapterDao(): ChapterDao
    abstract fun catalogDao(): CatalogDao
    abstract fun overrideDao(): OverrideDao
    abstract fun progressDao(): ProgressDao
    abstract fun sessionDao(): SessionDao
    abstract fun ebookProgressQueueDao(): EbookProgressQueueDao
    abstract fun downloadDao(): DownloadDao
    abstract fun highlightDao(): HighlightDao
}

/**
 * v1→v2 adds the `highlights` table only. Non-destructive on purpose: a bare
 * version bump under fallbackToDestructiveMigration would wipe the user's
 * download records and un-uploaded progress. SQL matches Room's generated
 * schema for [HighlightEntity] exactly (no index → nothing else to create).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `highlights` (" +
                "`id` TEXT NOT NULL, `serverId` TEXT NOT NULL, `libraryItemId` TEXT NOT NULL, " +
                "`locatorJson` TEXT NOT NULL, `text` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AudexDatabase =
        Room.databaseBuilder(context, AudexDatabase::class.java, "audex.db")
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun highlightDao(db: AudexDatabase) = db.highlightDao()

    @Provides fun serverDao(db: AudexDatabase) = db.serverDao()
    @Provides fun remoteItemDao(db: AudexDatabase) = db.remoteItemDao()
    @Provides fun chapterDao(db: AudexDatabase) = db.chapterDao()
    @Provides fun catalogDao(db: AudexDatabase) = db.catalogDao()
    @Provides fun overrideDao(db: AudexDatabase) = db.overrideDao()
    @Provides fun progressDao(db: AudexDatabase) = db.progressDao()
    @Provides fun sessionDao(db: AudexDatabase) = db.sessionDao()
    @Provides fun ebookProgressQueueDao(db: AudexDatabase) = db.ebookProgressQueueDao()
    @Provides fun downloadDao(db: AudexDatabase) = db.downloadDao()
}
