package com.bydmate.app.data.vehicle

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the in-vehicle helper daemon listening on 127.0.0.1:8765.
 * Stub in Phase 2 Group A — real TCP loopback implementation lands in Group B task B.4.
 *
 * Returns null on read failure, false on write/ping failure (daemon down,
 * socket timeout, parse error). Callers must treat null/false as "channel
 * unavailable, retry later".
 */
interface HelperClient {
    suspend fun read(dev: Int, fid: Int, tx: Int = 5): Long?
    suspend fun write(dev: Int, fid: Int, value: Int): Boolean
    suspend fun isAlive(): Boolean
}

@Singleton
class HelperClientImpl @Inject constructor() : HelperClient {
    override suspend fun read(dev: Int, fid: Int, tx: Int): Long? =
        throw NotImplementedError("populated in Group B task B.4")
    override suspend fun write(dev: Int, fid: Int, value: Int): Boolean =
        throw NotImplementedError("populated in Group B task B.4")
    override suspend fun isAlive(): Boolean = false
}
