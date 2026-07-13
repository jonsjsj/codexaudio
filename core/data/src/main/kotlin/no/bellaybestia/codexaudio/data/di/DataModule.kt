package no.bellaybestia.codexaudio.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import no.bellaybestia.codexaudio.auth.ServerTokenStore
import no.bellaybestia.codexaudio.data.CatalogRepositoryImpl
import no.bellaybestia.codexaudio.data.ServerRepositoryImpl
import no.bellaybestia.codexaudio.domain.repository.CatalogRepository
import no.bellaybestia.codexaudio.domain.repository.ServerRepository
import no.bellaybestia.codexaudio.network.abs.AbsClientFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    abstract fun catalogRepository(impl: CatalogRepositoryImpl): CatalogRepository

    @Binds
    abstract fun serverRepository(impl: ServerRepositoryImpl): ServerRepository

    companion object {
        @Provides
        @Singleton
        fun absClientFactory(tokenStore: ServerTokenStore): AbsClientFactory =
            AbsClientFactory(tokenStore)
    }
}
