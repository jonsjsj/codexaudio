package no.bellaybestia.audex.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import no.bellaybestia.audex.auth.AbsTokenRefresher
import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.data.AlignmentRepositoryImpl
import no.bellaybestia.audex.data.AuthRepositoryImpl
import no.bellaybestia.audex.data.BookmarksRepositoryImpl
import no.bellaybestia.audex.data.CatalogRepositoryImpl
import no.bellaybestia.audex.data.CodexSyncImpl
import no.bellaybestia.audex.data.DownloadsImpl
import no.bellaybestia.audex.data.EbookProgressWriterImpl
import no.bellaybestia.audex.data.HighlightsRepositoryImpl
import no.bellaybestia.audex.data.ReaderSettingsStoreImpl
import no.bellaybestia.audex.data.PlaybackControllerImpl
import no.bellaybestia.audex.data.PlaybackSettingsImpl
import no.bellaybestia.audex.data.StatsRepositoryImpl
import no.bellaybestia.audex.data.ReportsRepositoryImpl
import no.bellaybestia.audex.data.ServerRepositoryImpl
import no.bellaybestia.audex.data.StreamTokenResolverImpl
import no.bellaybestia.audex.data.ThemeSettingsImpl
import no.bellaybestia.audex.data.UpdateSettingsImpl
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.playback.BookmarksRepository
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.reader.EbookProgressWriter
import no.bellaybestia.audex.domain.reader.HighlightsRepository
import no.bellaybestia.audex.domain.reader.ReaderSettingsStore
import no.bellaybestia.audex.domain.repository.AuthRepository
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.domain.repository.ServerRepository
import no.bellaybestia.audex.domain.settings.CodexSync
import no.bellaybestia.audex.domain.settings.PlaybackSettings
import no.bellaybestia.audex.domain.settings.ActivityRecorder
import no.bellaybestia.audex.domain.settings.ActivityStatsRepository
import no.bellaybestia.audex.domain.settings.ReportsRepository
import no.bellaybestia.audex.domain.settings.StatsRepository
import no.bellaybestia.audex.domain.settings.ThemeSettings
import no.bellaybestia.audex.domain.settings.UpdateSettings
import no.bellaybestia.audex.data.MediaBrowseSourceImpl
import no.bellaybestia.audex.network.abs.AbsClientFactory
import no.bellaybestia.audex.player.MediaBrowseSource
import no.bellaybestia.audex.player.StreamTokenResolver
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun catalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    abstract fun serverRepository(impl: ServerRepositoryImpl): ServerRepository

    @Binds
    abstract fun authRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    abstract fun playbackController(impl: PlaybackControllerImpl): PlaybackController

    @Binds
    abstract fun streamTokenResolver(impl: StreamTokenResolverImpl): StreamTokenResolver

    @Binds
    abstract fun downloads(impl: DownloadsImpl): Downloads

    @Binds
    abstract fun bookmarksRepository(impl: BookmarksRepositoryImpl): BookmarksRepository

    @Binds
    abstract fun ebookProgressWriter(impl: EbookProgressWriterImpl): EbookProgressWriter

    @Binds
    abstract fun alignmentRepository(impl: AlignmentRepositoryImpl): AlignmentRepository

    @Binds
    abstract fun readerSettingsStore(impl: ReaderSettingsStoreImpl): ReaderSettingsStore

    @Binds
    abstract fun themeSettings(impl: ThemeSettingsImpl): ThemeSettings

    @Binds
    abstract fun updateSettings(impl: UpdateSettingsImpl): UpdateSettings

    @Binds
    abstract fun reportsRepository(impl: ReportsRepositoryImpl): ReportsRepository

    @Binds
    abstract fun mediaBrowseSource(impl: MediaBrowseSourceImpl): MediaBrowseSource

    @Binds
    abstract fun highlightsRepository(impl: HighlightsRepositoryImpl): HighlightsRepository

    @Binds
    abstract fun codexSync(impl: CodexSyncImpl): CodexSync

    @Binds
    abstract fun playbackSettings(impl: PlaybackSettingsImpl): PlaybackSettings

    @Binds
    abstract fun statsRepository(impl: StatsRepositoryImpl): StatsRepository

    @Binds
    abstract fun activityStatsRepository(impl: ActivityStatsRepositoryImpl): ActivityStatsRepository

    @Binds
    abstract fun activityRecorder(impl: ActivityRecorderImpl): ActivityRecorder

    companion object {
        @Provides
        @Singleton
        fun absClientFactory(
            tokenStore: ServerTokenStore,
            refresher: AbsTokenRefresher,
        ): AbsClientFactory = AbsClientFactory(tokenStore, refresher)
    }
}
