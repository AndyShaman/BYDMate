package com.bydmate.app.data.remote

import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.vehicle.VehicleApi
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class AliceApertureController @Inject constructor(
    private val vehicleApi: VehicleApi,
) {
    suspend fun positionWindow(action: String, target: Int, speed: Int?): Result<Unit> {
        if (target !in 0..100) return Result.failure(IllegalArgumentException("invalid_window_position"))

        val channel = windowChannel(action) ?: return Result.failure(IllegalArgumentException("unknown_window"))
        if (target == 0) return vehicleApi.dispatch(channel.close)
        if (target == 100) {
            ActionDispatcher.speedGateBlockReason(channel.open, speed)?.let {
                return Result.failure(IllegalStateException(it.javaClass.simpleName))
            }
            return vehicleApi.dispatch(channel.open)
        }

        val start = channel.read()
            ?: return Result.failure(IllegalStateException("window_position_unavailable"))
        if (abs(start - target) <= 2) return Result.success(Unit)

        val opening = target > start
        if (opening) {
            ActionDispatcher.speedGateBlockReason(channel.open, speed)?.let {
                return Result.failure(IllegalStateException(it.javaClass.simpleName))
            }
        }

        val native = channel.write(target)
        if (native.isSuccess && waitForWindow(channel, start, target, opening, 1500L)) {
            return Result.success(Unit)
        }

        val current = channel.read() ?: start
        val movingOpen = target > current
        if (movingOpen) {
            ActionDispatcher.speedGateBlockReason(channel.open, speed)?.let {
                return Result.failure(IllegalStateException(it.javaClass.simpleName))
            }
        }

        val started = vehicleApi.dispatch(if (movingOpen) channel.open else channel.close)
        if (started.isFailure) return started

        val deadline = System.currentTimeMillis() + 12_000L
        var reached = false
        while (System.currentTimeMillis() < deadline) {
            delay(40L)
            val value = channel.read() ?: continue
            if ((movingOpen && value >= target) || (!movingOpen && value <= target) || abs(value - target) <= 1) {
                reached = true
                break
            }
        }

        val stopped = vehicleApi.dispatch(channel.stop)
        if (stopped.isFailure) return stopped
        delay(120L)
        val first = channel.read()
        delay(120L)
        val second = channel.read()
        if (first != null && second != null && abs(second - first) > 2) {
            vehicleApi.dispatch(channel.stop)
        }
        delay(180L)
        val final = channel.read()

        return if (reached && final != null && abs(final - target) <= 6) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("window_position_miss"))
        }
    }

    suspend fun positionSunroof(target: Int, data: DiParsData?): Result<Unit> {
        if (target !in 0..100) return Result.failure(IllegalArgumentException("invalid_sunroof_position"))
        if (target == 0) return vehicleApi.dispatch("天窗打开0")
        if (target == 50) return gatedDispatch("天窗打开50", data?.speed)
        if (target == 100) return gatedDispatch("天窗打开100", data?.speed)

        val start = data?.sunroof ?: return Result.failure(IllegalStateException("sunroof_position_unavailable"))
        if (abs(start - target) <= 2) return Result.success(Unit)

        val command = if (target > start) "天窗打开100" else "天窗打开0"
        if (target > start) {
            ActionDispatcher.speedGateBlockReason(command, data.speed)?.let {
                return Result.failure(IllegalStateException(it.javaClass.simpleName))
            }
        }

        val started = vehicleApi.dispatch(command)
        if (started.isFailure) return started

        val deadline = System.currentTimeMillis() + 15_000L
        var last = start
        var reached = false
        while (System.currentTimeMillis() < deadline) {
            delay(75L)
            val value = latestSunroof() ?: continue
            last = value
            if ((target > start && value >= target) || (target < start && value <= target) || abs(value - target) <= 2) {
                reached = true
                break
            }
        }

        val stopped = vehicleApi.dispatch("天窗停止")
        if (stopped.isFailure) return stopped
        delay(300L)
        val final = latestSunroof() ?: last

        return if (reached && abs(final - target) <= 4) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("sunroof_position_miss"))
        }
    }

    @Volatile
    var latestData: DiParsData? = null

    private fun latestSunroof(): Int? = latestData?.sunroof

    private suspend fun gatedDispatch(command: String, speed: Int?): Result<Unit> {
        ActionDispatcher.speedGateBlockReason(command, speed)?.let {
            return Result.failure(IllegalStateException(it.javaClass.simpleName))
        }
        return vehicleApi.dispatch(command)
    }

    private suspend fun waitForWindow(
        channel: WindowChannel,
        start: Int,
        target: Int,
        opening: Boolean,
        timeoutMs: Long,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var moved = false
        while (System.currentTimeMillis() < deadline) {
            delay(100L)
            val value = channel.read() ?: continue
            if (abs(value - start) >= 2) moved = true
            if (abs(value - target) <= 6) return true
            if (moved && ((opening && value > target + 6) || (!opening && value < target - 6))) return false
        }
        return false
    }

    private fun windowChannel(action: String): WindowChannel? = when (action) {
        "window.driver.position" -> WindowChannel(
            "主驾打开100", "主驾打开0", "主驾停止",
            { vehicleApi.readWindowDriver() }, { vehicleApi.writeWindowDriver(it) },
        )
        "window.passenger.position" -> WindowChannel(
            "副驾打开100", "副驾打开0", "副驾停止",
            { vehicleApi.readWindowPassenger() }, { vehicleApi.writeWindowPassenger(it) },
        )
        "window.rear_left.position" -> WindowChannel(
            "后左打开100", "后左打开0", "后左停止",
            { vehicleApi.readWindowRearLeft() }, { vehicleApi.writeWindowRearLeft(it) },
        )
        "window.rear_right.position" -> WindowChannel(
            "后右打开100", "后右打开0", "后右停止",
            { vehicleApi.readWindowRearRight() }, { vehicleApi.writeWindowRearRight(it) },
        )
        else -> null
    }

    private data class WindowChannel(
        val open: String,
        val close: String,
        val stop: String,
        val read: suspend () -> Int?,
        val write: suspend (Int) -> Result<Unit>,
    )
}
