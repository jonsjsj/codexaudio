package no.bellaybestia.codexaudio.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    ],
    version = 1,
    exportSchema = false,
)
abstract class CodexAudioDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun remoteItemDao(): RemoteItemDao
    abstract fun chapterDao(): ChapterDao
    abstract fun catalogDao(): CatalogDao
    abstract fun overrideDao(): OverrideDao
    abstract fun progressDao(): ProgressDao
    abstract fun sessionDao(): SessionDao
    abstract fun ebookProgressQueueDao(): EbookProgressQueueDao
    abstract fun downloadDao(): DownloadDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): CodexAudioDatabase =
        Room.databaseBuilder(context, CodexAudioDatabase::class.java, "codex-audio.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun serverDao(db: CodexAudioDatabase) = db.serverDao()
    @Provides fun remoteItemDao(db: CodexAudioDatabase) = db.remoteItemDao()
    @Provides fun chapterDao(db: CodexAudioDatabase) = db.chapterDao()
    @Provides fun catalogDao(db: CodexAudioDatabase) = db.catalogDao()
    @Provides fun overrideDao(db: CodexAudioDatabase) = db.overrideDao()
    @Provides fun progressDao(db: CodexAudioDatabase) = db.progressDao()
    @Provides fun sessionDao(db: CodexAudioDatabase) = db.sessionDao()
    @Provides fun ebookProgressQueueDao(db: CodexAudioDatabase) = db.ebookProgressQueueDao()
    @Provides fun downloadDao(db: CodexAudioDatabase) = db.downloadDao()
}
