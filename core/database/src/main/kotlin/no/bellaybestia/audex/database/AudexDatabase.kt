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
        ActivityEntity::class,
        PodcastEntity::class,
        EpisodeEntity::class,
        EpisodeProgressEntity::class,
    ],
    version = 4,
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
    abstract fun activityDao(): ActivityDao
    abstract fun podcastDao(): PodcastDao
    abstract fun episodeDao(): EpisodeDao
    abstract fun episodeProgressDao(): EpisodeProgressDao
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

/** v2→v3 adds the `activity` table (per-book, per-day listen/read seconds). SQL
 * matches Room's generated schema for [ActivityEntity] exactly. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity` (" +
                "`serverId` TEXT NOT NULL, `libraryItemId` TEXT NOT NULL, `epochDay` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, `seconds` REAL NOT NULL, " +
                "PRIMARY KEY(`serverId`, `libraryItemId`, `epochDay`, `kind`))",
        )
    }
}

/**
 * v3→v4 adds the podcast pipeline: `podcasts`, `episodes` (+ its two indices),
 * `episode_progress`, and an `episodeId` column on `pending_sessions` so podcast
 * listens upload with the episode attached. Non-destructive — SQL matches Room's
 * generated schema for the new entities exactly.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `podcasts` (" +
                "`serverId` TEXT NOT NULL, `libraryItemId` TEXT NOT NULL, `libraryId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `author` TEXT, `description` TEXT, `feedUrl` TEXT, " +
                "`autoDownload` INTEGER NOT NULL, `autoDownloadSchedule` TEXT, " +
                "`maxEpisodesToKeep` INTEGER NOT NULL, `numEpisodes` INTEGER NOT NULL, " +
                "`updatedAtRemote` INTEGER NOT NULL, " +
                "PRIMARY KEY(`serverId`, `libraryItemId`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `episodes` (" +
                "`serverId` TEXT NOT NULL, `libraryItemId` TEXT NOT NULL, `episodeId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `subtitle` TEXT, `description` TEXT, `pubDate` TEXT, " +
                "`publishedAt` INTEGER, `durationS` REAL, `sizeBytes` INTEGER, `season` TEXT, " +
                "`episodeNum` TEXT, `idx` INTEGER NOT NULL, " +
                "PRIMARY KEY(`serverId`, `libraryItemId`, `episodeId`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_episodes_serverId_libraryItemId` " +
                "ON `episodes` (`serverId`, `libraryItemId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_episodes_publishedAt` ON `episodes` (`publishedAt`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `episode_progress` (" +
                "`serverId` TEXT NOT NULL, `libraryItemId` TEXT NOT NULL, `episodeId` TEXT NOT NULL, " +
                "`pct` REAL NOT NULL, `currentTimeS` REAL, `isFinished` INTEGER NOT NULL, " +
                "`lastUpdate` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                "PRIMARY KEY(`serverId`, `libraryItemId`, `episodeId`))",
        )
        db.execSQL("ALTER TABLE `pending_sessions` ADD COLUMN `episodeId` TEXT")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AudexDatabase =
        Room.databaseBuilder(context, AudexDatabase::class.java, "audex.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun highlightDao(db: AudexDatabase) = db.highlightDao()
    @Provides fun activityDao(db: AudexDatabase) = db.activityDao()
    @Provides fun podcastDao(db: AudexDatabase) = db.podcastDao()
    @Provides fun episodeDao(db: AudexDatabase) = db.episodeDao()
    @Provides fun episodeProgressDao(db: AudexDatabase) = db.episodeProgressDao()

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
