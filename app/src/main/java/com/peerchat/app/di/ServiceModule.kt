package com.peerchat.app.di

import android.content.Context
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.engine.DocumentService
import com.peerchat.app.engine.ModelManifestService
import com.peerchat.app.engine.ModelRepository
import com.peerchat.app.engine.ModelService
import com.peerchat.app.engine.ModelStateCache
import com.peerchat.app.engine.SearchService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideModelManifestService(@ApplicationContext context: Context): ModelManifestService {
        return ModelManifestService(context)
    }

    @Provides
    @Singleton
    fun provideModelRepository(
        @ApplicationContext context: Context,
        manifestService: ModelManifestService
    ): ModelRepository {
        return ModelRepository(context, manifestService)
    }

    @Provides
    @Singleton
    fun provideModelService(modelRepository: ModelRepository): ModelService {
        return ModelService(modelRepository)
    }

    @Provides
    @Singleton
    fun provideDocumentService(
        @ApplicationContext context: Context,
        repository: PeerChatRepository
    ): DocumentService {
        return DocumentService(context, repository)
    }

    @Provides
    @Singleton
    fun provideSearchService(repository: PeerChatRepository): SearchService {
        return SearchService(repository)
    }

    @Provides
    @Singleton
    fun provideModelStateCache(
        modelRepository: ModelRepository
    ): ModelStateCache {
        return ModelStateCache(modelRepository)
    }

    @Provides
    @Singleton
    fun provideRetriever(): com.peerchat.rag.Retriever {
        return com.peerchat.rag.RetrieverProvider.instance
    }
}
