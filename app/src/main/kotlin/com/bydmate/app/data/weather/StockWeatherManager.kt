package com.bydmate.app.data.weather

import android.location.Location
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton

enum class StockWeatherState { Disabled, WaitingLocation, WaitingInternet, NoLocationPermission, Updating, Updated, Unavailable, Failed }

@Singleton
class StockWeatherManager @Inject constructor(val preferences: StockWeatherPreferences, private val client: StockWeatherClient) {
    private val _state = MutableStateFlow(StockWeatherState.Disabled)
    val state = _state.asStateFlow()
    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var job: Job? = null

    /** Uses TrackingService's GPS and network events; never starts another location poller. */
    fun start(scope: CoroutineScope, locations: StateFlow<Location?>, networkEdges: StateFlow<Long>) {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(preferences.enabled, preferences.intervalMinutes) { enabled, minutes -> enabled to minutes }
                .collectLatest { (enabled, minutes) ->
                    if (!enabled) {
                        _state.value = StockWeatherState.Disabled
                    } else runUpdates(locations, networkEdges, minutes)
                }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = StockWeatherState.Disabled
    }

    fun requestRefresh() {
        if (preferences.enabled.value && _state.value != StockWeatherState.Updating) refreshes.tryEmit(Unit)
    }

    fun onRefreshClick(packageName: String?, viewId: String?) {
        if (packageName == StockWeatherClient.PACKAGE && viewId == StockWeatherClient.REFRESH_VIEW) requestRefresh()
    }

    private suspend fun runUpdates(locations: StateFlow<Location?>, networkEdges: StateFlow<Long>, minutes: Int) = coroutineScope {
        val timerEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        var timer: Job? = null
        val intervalMs = minutes * 60_000L
        val previous = preferences.lastSuccessMs.value
        var nextAt = if (previous in 1..System.currentTimeMillis()) previous + intervalMs else 0L
        fun schedule(ms: Long) {
            timer?.cancel()
            timer = launch { delay(ms.coerceAtLeast(1)); timerEvents.emit(Unit) }
        }
        if (nextAt > System.currentTimeMillis()) {
            _state.value = StockWeatherState.Updated
            schedule(nextAt - System.currentTimeMillis())
        }
        try {
            merge(locations.map { false }, networkEdges.map { false }, refreshes.map { true }, timerEvents.map { true })
                .onStart { emit(false) }.collect { forced ->
                    if (!forced && System.currentTimeMillis() < nextAt) return@collect
                    timer?.cancel()
                    nextAt = 0L
                    if (!client.hasLocationPermission()) {
                        _state.value = StockWeatherState.NoLocationPermission
                        return@collect
                    }
                    val location = locations.value?.takeIf { fresh(it, System.currentTimeMillis()) }
                    if (location == null) {
                        _state.value = StockWeatherState.WaitingLocation
                        return@collect
                    }
                    if (!client.hasNetwork()) {
                        _state.value = StockWeatherState.WaitingInternet
                        return@collect
                    }
                    _state.value = StockWeatherState.Updating
                    val success = try {
                        withTimeout(45_000L) {
                            val requestJob = coroutineContext[Job]!!
                            client.refresh(location) { preferences.enabled.value && requestJob.isActive }
                        }
                    } catch (_: TimeoutCancellationException) {
                        coroutineContext.ensureActive()
                        _state.value = StockWeatherState.Failed
                        false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: StockWeatherClient.Unavailable) {
                        _state.value = StockWeatherState.Unavailable
                        false
                    } catch (e: Exception) {
                        Log.w("StockWeather", "refresh failed: ${e.javaClass.simpleName}")
                        _state.value = StockWeatherState.Failed
                        false
                    }
                    if (success) {
                        preferences.recordSuccess(System.currentTimeMillis())
                        _state.value = StockWeatherState.Updated
                        Log.i("StockWeather", "stock card refreshed")
                    } else if (_state.value == StockWeatherState.Updating) _state.value = StockWeatherState.Failed
                    val waitMs = if (success) intervalMs else 5 * 60_000L
                    nextAt = System.currentTimeMillis() + waitMs
                    schedule(waitMs)
                }
        } finally {
            timer?.cancel()
        }
    }

    private fun fresh(location: Location, nowMs: Long): Boolean = nowMs - location.time in 0..30 * 60_000L &&
        location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
        location.longitude.isFinite() && location.longitude in -180.0..180.0
}
