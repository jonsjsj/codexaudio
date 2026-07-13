package no.bellaybestia.audex.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import no.bellaybestia.audex.auth.ServerTokenStore
import no.bellaybestia.audex.data.CatalogRepositoryImpl
import no.bellaybestia.audex.data.ServerRepositoryImpl
import no.bellaybestia.audex.domain.repository.CatalogRepository
import no.bellaybestia.audex.domain.repository.ServerRepository
import no.bellaybestia.audex.network.abs.AbsClientFactory
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
