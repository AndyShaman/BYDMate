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
     * did not update as expected.
     */
    class ReadbackMismatch(action: String, details: String) : VehicleWriteError(action, details)

    /**
     * Readback returned the -10011 permanent sentinel ("no data / permission denied").
     * Usually transient — caller may retry after a state change.
     */
    class Sentinel(action: String, details: String = "value=-10011 sentinel returned")
        : VehicleWriteError(action, details)

    /**
     * HelperClient returned false for a non-validated entry. The action is in
     * the competitor allowlist but has not been live-confirmed on Leopard 3.
     */
    class Unsupported(action: String, details: String = "not validated on this vehicle")
        : VehicleWriteError(action, details)
}
