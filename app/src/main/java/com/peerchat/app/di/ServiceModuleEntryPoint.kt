package com.peerchat.app.di

import com.peerchat.app.engine.AndroidEmbeddingService
import com.peerchat.app.engine.ModelService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Entry point for accessing services provided by ServiceModule
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServiceModuleEntryPoint {
    fun provideAndroidEmbeddingService(): AndroidEmbeddingService
    fun provideModelService(): ModelService
}

