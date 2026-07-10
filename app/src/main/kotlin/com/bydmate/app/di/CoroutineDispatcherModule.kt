package com.bydmate.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Named
import javax.inject.Singleton

/** Provides the IO dispatcher for constructor injection (Hilt ignores Kotlin default values). */
@Module
@InstallIn(SingletonComponent::class)
object CoroutineDispatcherModule {

    @Provides
    @Singleton
    @Named("io")
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
