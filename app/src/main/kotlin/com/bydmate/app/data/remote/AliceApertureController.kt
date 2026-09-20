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
    @Volatile
    var latestData: DiParsData? = null

    suspend fun positionWindow(action: String, target: Int, speed: Int?): Result<Unit> {
        if (target !in 0..100) return failure("invalid_window_position")
        val channel = windowChannel(action) ?: return failure("unknown_window")
        windowEndpoint(channel, target, speed)?.let { return it }

        val start = channel.read() ?: return failure("window_position_unavailable")
        if (near(start, target, 2)) return Result.success(Unit)
        blockOpening(channel.open, target > start, speed)?.let { return it }

        if (tryNativeWindowPosition(channel, target)) return Result.success(Unit)
        return moveWindowToTarget(channel, target, start, speed)
    }

    suspend fun positionSunroof(target: Int, data: DiParsData?): Result<Unit> {
        if (target !in 0..100) return failure("invalid_sunroof_position")
        sunroofEndpoint(target, data?.speed)?.let { return it }

        val start = data?.sunroof ?: return failure("sunroof_position_unavailable")
        if (near(start, target, 2)) return Result.success(Unit)

        val opening = target > start
        val command = if (opening) SUNROOF_OPEN else SUNROOF_CLOSE
        blockOpening(command, opening, data.speed)?.let { return it }

        val started = vehicleApi.dispatch(command)
        if (started.isFailure) return started

        val reached = waitForTarget(
            { latestData?.sunroof },
            WaitSpec(target, opening, 15_000L, 75L, 2),
        )
        val stopped = vehicleApi.dispatch(SUNROOF_STOP)
        if (stopped.isFailure) return stopped

        delay(300L)
        val final = latestData?.sunroof ?: reached
        return if (reached != null && final != null && near(final, target, 4)) {
            Result.success(Unit)
        } else {
            failure("sunroof_position_miss")
        }
    }

    private suspend fun windowEndpoint(
        channel: WindowChannel,
        target: Int,
        speed: Int?,
    ): Result<Unit>? = when (target) {
        0 -> vehicleApi.dispatch(channel.close)
        100 -> blockOpening(channel.open, true, speed) ?: vehicleApi.dispatch(channel.open)
        else -> null
    }

    private suspend fun sunroofEndpoint(target: Int, speed: Int?): Result<Unit>? = when (target) {
        0 -> vehicleApi.dispatch(SUNROOF_CLOSE)
        50 -> blockOpening(SUNROOF_HALF, true, speed) ?: vehicleApi.dispatch(SUNROOF_HALF)
        100 -> blockOpening(SUNROOF_OPEN, true, speed) ?: vehicleApi.dispatch(SUNROOF_OPEN)
        else -> null
    }

    private suspend fun tryNativeWindowPosition(channel: WindowChannel, target: Int): Boolean {
        if (channel.write(target).isFailure) return false
        val reached = waitForTarget(
            channel.read,
            WaitSpec(target, true, 1500L, 100L, 6, false),
        ) ?: return false
        delay(150L)
        val settled = channel.read() ?: reached
        return near(settled, target, 6)
    }

    private suspend fun moveWindowToTarget(
        channel: WindowChannel,
        target: Int,
        start: Int,
        speed: Int?,
    ): Result<Unit> {
        val current = channel.read() ?: start
        val opening = target > current
        blockOpening(channel.open, opening, speed)?.let { return it }

        val started = vehicleApi.dispatch(if (opening) channel.open else channel.close)
        if (started.isFailure) return started

        val reached = waitForTarget(
            channel.read,
            WaitSpec(target, opening, 12_000L, 40L, 1),
        )
        val final = stopWindow(channel)
        return if (reached != null && final != null && near(final, target, 6)) {
            Result.success(Unit)
        } else {
            failure("window_position_miss")
        }
    }

    private suspend fun stopWindow(channel: WindowChannel): Int? {
        val firstStop = vehicleApi.dispatch(channel.stop)
        if (firstStop.isFailure) return null
        delay(120L)
        val first = channel.read()
        delay(120L)
        val second = channel.read()
        if (first != null && second != null && abs(second - first) > 2) {
            vehicleApi.dispatch(channel.stop)
        }
        delay(180L)
        return channel.read()
    }

    private suspend fun waitForTarget(
        read: suspend () -> Int?,
        spec: WaitSpec,
    ): Int? {
        val deadline = System.currentTimeMillis() + spec.timeoutMs
        while (System.currentTimeMillis() < deadline) {
            delay(spec.sampleMs)
            val value = read()
            if (value != null && reached(value, spec)) return value
        }
        return null
    }

    private fun blockOpening(command: String, opening: Boolean, speed: Int?): Result<Unit>? {
        if (!opening) return null
        val reason = ActionDispatcher.speedGateBlockReason(command, speed) ?: return null
        return failure(reason.javaClass.simpleName)
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

    companion object {
        private const val SUNROOF_OPEN = "天窗打开100"
        private const val SUNROOF_HALF = "天窗打开50"
        private const val SUNROOF_CLOSE = "天窗打开0"
        private const val SUNROOF_STOP = "天窗停止"
    }
}

private data class WaitSpec(
    val target: Int,
    val opening: Boolean,
    val timeoutMs: Long,
    val sampleMs: Long,
    val tolerance: Int,
    val directional: Boolean = true,
)

private fun reached(value: Int, spec: WaitSpec): Boolean {
    if (near(value, spec.target, spec.tolerance)) return true
    if (!spec.directional) return false
    return if (spec.opening) value >= spec.target else value <= spec.target
}

private fun near(value: Int, target: Int, tolerance: Int): Boolean =
    abs(value - target) <= tolerance

private fun failure(message: String): Result<Unit> =
    Result.failure(IllegalStateException(message))
