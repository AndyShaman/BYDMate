package com.bydmate.app.data.loop

import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.DiParsData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of NativeParsReader. All read consumers subscribe to [flow].
 * Cadence is decided per tick via [LoopFsm.classify]; backoff applies when
 * fetch() returns null.
 */
@Singleton
class SharedAdaptiveLoop constructor(
    private val parsReader: ParsReader,
    private val lastStateDao: LastStateDao,
    private val energyDataReader: EnergyDataReader,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cadence: CadenceConfig = CadenceConfig.default(),
) {
    private val _flow = MutableSharedFlow<DiParsData>(
        replay = 1, extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val flow: SharedFlow<DiParsData> = _flow.asSharedFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope): Job {
        job?.takeIf { it.isActive }?.let { return it }
        job = scope.launch(dispatcher) { runLoop() }
        return job!!
    }

    fun stop() { job?.cancel(); job = null }

    private suspend fun runLoop() {
        var consecutiveNull = 0
        while (true) {
            val data = runCatching { parsReader.fetch() }.getOrNull()
            if (data == null) {
                consecutiveNull++
                _connected.value = false
                val backoff = (cadence.intervalFor(LoopState.IDLE) * pow15(consecutiveNull))
                    .coerceAtMost(CadenceConfig.MAX_POLL_INTERVAL_MS)
                delay(backoff)
                continue
            }
            consecutiveNull = 0
            _connected.value = true
            _flow.emit(data)
            persistSnapshot(data)
            delay(cadence.intervalFor(LoopFsm.classify(data)))
        }
    }

    private suspend fun persistSnapshot(data: DiParsData) {
        val now = System.currentTimeMillis()
        val prev = lastStateDao.getCurrent()
        val ignition = data.powerState
        lastStateDao.upsert(
            LastStateEntity(
                id = 1,
                ts = now,
                soc = data.soc,
                mileage = data.mileage,
                totalElec = data.totalElecConsumption,
                ignition = ignition,
                openTripId = prev?.openTripId,
                tripStartTs = prev?.tripStartTs,
                tripStartSoc = prev?.tripStartSoc,
                tripStartMileage = prev?.tripStartMileage,
                tripStartTotalElec = prev?.tripStartTotalElec,
                energydataAvailable = if (energyDataReader.isAvailable()) 1 else 0,
            )
        )
    }

    private fun pow15(n: Int): Long {
        var v = 1.0
        repeat(n) { v *= 1.5 }
        return v.toLong().coerceAtLeast(1L)
    }
}
