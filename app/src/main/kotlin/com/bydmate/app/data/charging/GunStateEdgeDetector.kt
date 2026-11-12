package com.bydmate.app.data.charging

/**
 * Tracks `gunConnectState` across polls and reports the connected→disconnected
 * edge — the moment a charging session ends and we should finalize a row.
 *
 * Why a separate class? The pre-2.5.11 live-edge detector lived inside
 * TrackingService and consumed `data.chargeGunState` from DiPlus. On Leopard 3
 * DiPlus often runs in reduced-payload mode and omits `ChargeGun` entirely,
 * so the DiPlus-driven edge never fired and one real charging session was
 * dropped (no end-of-charging row written). Sourcing the gun state from
 * autoservice (system SDK) instead makes this independent of DiPlus, but
 * the bookkeeping was easier to reason about — and easier to unit-test —
 * once extracted from the polling loop.
 *
 * Encoding (autoservice fid 876609586, dev=1009):
 *   1 = NONE        (no gun)
 *   2 = AC
 *   3 = DC
 *   4 = AC_DC       (treated as DC by ChargingTypeClassifier)
 *   5 = VTOL
 *
 * Edge rule: previous state was a known non-NONE value AND current is NONE.
 * A null reading is a transient autoservice glitch; we keep the previous
 * value and refuse to fire — phantom edges from sentinel reads were the
 * exact failure mode this class was written to prevent.
 */
class GunStateEdgeDetector(initial: Int? = null) {

    @Volatile
    var previous: Int? = initial
        private set

    /** Returns true when the current sample crosses connected→disconnected. */
    fun onSample(current: Int?): Boolean {
        if (current == null) return false
        val prev = previous
        previous = current
        return prev != null && prev != GUN_STATE_NONE && current == GUN_STATE_NONE
    }

    fun reset() {
        previous = null
    }

    companion object {
        /** Gun fid value meaning "no gun"; every gun-state check in the app compares with it. */
        const val GUN_STATE_NONE = 1
    }
}
