package com.bydmate.app.data.carstatus

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the stock `content://com.byd.carStatusProvider/car_status` table, read-only. */
@Singleton
class CarStatusReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** One query on the IO dispatcher; null when the provider is missing or refuses. */
    suspend fun read(): CarServiceStatus? = TODO("not implemented")

    /** Emits when one of the watched keys changes (per-key observers, debounced). */
    fun changes(): Flow<Unit> = TODO("not implemented")
}
