package com.peerchat.app.di

import com.peerchat.app.engine.PromptComposer
import com.peerchat.app.engine.StreamingEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

@Module
@InstallIn(ViewModelComponent::class)
object ViewModelModule {

    @Provides
    fun provideStreamingEngine(): StreamingEngine = StreamingEngine

    @Provides
    fun providePromptComposer(): PromptComposer = PromptComposer

}
