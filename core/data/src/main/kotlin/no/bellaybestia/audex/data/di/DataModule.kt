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
import no.bellaybestia.audex.data.CatalogRepositoryImpl
import no.bellaybestia.audex.data.DownloadsImpl
import no.bellaybestia.audex.data.EbookProgressWriterImpl
import no.bellaybestia.audex.data.PlaybackControllerImpl
import no.bellaybestia.audex.data.ServerRepositoryImpl
import no.bellaybestia.audex.data.StreamTokenResolverImpl
import no.bellaybestia.audex.domain.download.Downloads
import no.bellaybestia.audex.domain.playback.PlaybackController
import no.bellaybestia.audex.domain.reader.AlignmentRepository
import no.bellaybestia.audex.domain.reader.EbookProgressWriter
import no.bellaybestia.audex.domain.repository.AuthRepository
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.domain.repository.ServerRepository
import no.bellaybestia.audex.network.abs.AbsClientFactory
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
    abstract fun ebookProgressWriter(impl: EbookProgressWriterImpl): EbookProgressWriter

    @Binds
    abstract fun alignmentRepository(impl: AlignmentRepositoryImpl): AlignmentRepository

    companion object {
        @Provides
        @Singleton
        fun absClientFactory(
            tokenStore: ServerTokenStore,
            refresher: AbsTokenRefresher,
        ): AbsClientFactory = AbsClientFactory(tokenStore, refresher)
    }
}
