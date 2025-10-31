package com.peerchat.app.di

import android.content.Context
import com.peerchat.app.BuildConfig
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.data.db.PeerDatabase
import com.peerchat.data.db.PeerDatabaseProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): PeerDatabase {
        return PeerDatabaseProvider.get(context, BuildConfig.DEBUG)
    }

    @Provides
    @Singleton
    fun provideRepository(database: PeerDatabase): PeerChatRepository {
        return PeerChatRepository(database)
    }
}
