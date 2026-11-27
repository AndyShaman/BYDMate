package com.bydmate.app.camera

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import kotlinx.coroutines.delay

/**
 * Decides how to obtain SYSTEM_ALERT_WINDOW before showing blind-spot windows (#476).
 */
object BlindSpotOverlayAccess {
    enum class Step { NONE, GRANT_VIA_HELPER, OPEN_SYSTEM_SCREEN }

    private const val GRANT_POLL_ATTEMPTS = 5
    private const val GRANT_POLL_MS = 100L

    fun next(canDraw: Boolean, helperAvailable: Boolean): Step = when {
        canDraw -> Step.NONE
        helperAvailable -> Step.GRANT_VIA_HELPER
        else -> Step.OPEN_SYSTEM_SCREEN
    }

    /** Background-safe overlay grant via helper only (never starts an activity, #476). */
    suspend fun ensureViaHelper(context: Context, helper: HelperClient) {
        val step = next(canDraw = Settings.canDrawOverlays(context), helperAvailable = true)
        if (step != Step.GRANT_VIA_HELPER) return
        Log.i(BLIND_SPOT_TAG, "granting overlay permission via helper")
        if (helper.grantOverlayPermission() && !Settings.canDrawOverlays(context)) {
            repeat(GRANT_POLL_ATTEMPTS) {
                delay(GRANT_POLL_MS)
                if (Settings.canDrawOverlays(context)) return
            }
        }
    }
}
