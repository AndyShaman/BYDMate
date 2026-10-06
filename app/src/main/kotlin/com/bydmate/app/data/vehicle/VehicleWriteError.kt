package com.bydmate.app.data.vehicle

/**
 * Typed failure reasons for VehicleApi write operations.
 *
 * Extends RuntimeException so instances fit inside kotlin.Result<Unit> as
 * the exception carrier. The message is "$action: $details" — stable for
 * logging without reflection.
 *
 * Not data classes — sealed RuntimeException subclasses behave unexpectedly
 * with auto-generated equals/hashCode; plain class is sufficient here.
 */
sealed class VehicleWriteError(
    val action: String,
    val details: String,
) : RuntimeException("$action: $details") {

    /** Action name not present in WriteAllowlist (competitor JSON + LIVE_VALIDATED). */
    class AllowlistMiss(action: String, details: String = "no allowlist entry")
        : VehicleWriteError(action, details)

    /** Requested value outside the valueMin..valueMax range declared in WriteEntry. */
    class OutOfRange(action: String, details: String) : VehicleWriteError(action, details)

    /** HelperClient threw an exception or returned false for a validated entry. */
    class HelperUnreachable(action: String, details: String) : VehicleWriteError(action, details)

    /**
     * Readback value from autoservice differs from the written value.
     * Indicates the command was accepted by the daemon but the vehicle state
     * did not update as expected. [stuckPanes] lists the side windows that never moved
     * (empty for any other readback): the driver's text is built from it in the app
     * language, [details] stay the log line.
     */
    class ReadbackMismatch(action: String, details: String, val stuckPanes: List<WindowPane> = emptyList())
        : VehicleWriteError(action, details)

    /**
     * Readback returned the -10011 permanent sentinel ("no data / permission denied").
     * Usually transient — caller may retry after a state change.
     */
    class Sentinel(action: String, details: String = "value=-10011 sentinel returned")
        : VehicleWriteError(action, details)

    /**
     * The car reports the function as absent (state 0; 65535 = no CAN link is not). Raised by
     * SteeringHeatChannel, DriveModeChannel (support flag != 0) and HudSwitchChannel (no HUD
     * config); ActionDispatcher words it
     * per action, so a new producer must extend that mapping.
     */
    class NotEquipped(action: String, details: String = "function absent on this car")
        : VehicleWriteError(action, details)

    /**
     * The car's own state forbids the command right now. Raised only by DriveModeChannel: no
     * drive mode change while the car holds the emergency flotation mode.
     */
    class StateBlocked(action: String, details: String) : VehicleWriteError(action, details)

    /**
     * Refused by a speed limit enforced below ActionDispatcher: a terrain drive mode above
     * 15 km/h, or at unknown [speed] (null). Raised only by DriveModeChannel.
     */
    class SpeedBlocked(action: String, details: String, val speed: Int?) : VehicleWriteError(action, details)

    /**
     * HelperClient returned false for a non-validated entry. The action is in
     * the competitor allowlist but has not been live-confirmed on Leopard 3.
     */
    class Unsupported(action: String, details: String = "not validated on this vehicle")
        : VehicleWriteError(action, details)
}

/** Side window a window readback verdict names. */
enum class WindowPane {
    DRIVER, PASSENGER, REAR_LEFT, REAR_RIGHT, OTHER;

    companion object {
        /** Pane an allowlist window action (window_driver_pos, window_rear_left_open, …) moves. */
        fun of(actionName: String): WindowPane = when {
            actionName.startsWith("window_driver") -> DRIVER
            actionName.startsWith("window_passenger") -> PASSENGER
            actionName.startsWith("window_rear_left") -> REAR_LEFT
            actionName.startsWith("window_rear_right") -> REAR_RIGHT
            else -> OTHER
        }
    }
}
