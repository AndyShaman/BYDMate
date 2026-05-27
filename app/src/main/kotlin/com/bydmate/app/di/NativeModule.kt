package com.bydmate.app.di

import com.bydmate.app.data.`native`.NativeParsReader
import com.bydmate.app.data.`native`.ParsReader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class NativeModule {

    @Binds
    @Singleton
    abstract fun bindParsReader(impl: NativeParsReader): ParsReader
}
