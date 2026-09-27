package com.bydmate.app.data.telegram

import android.content.Context
import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.HelperReplaceHook
import com.bydmate.app.helper.offreport.OffReportState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs inside [com.bydmate.app.data.vehicle.HelperBootstrap] right before a live daemon of another
 * version is killed (an app update on a running car, whichever caller ensures the daemon first):
 * keeps that daemon's last power-off outcome on disk for [PowerOffArmer]'s start-up check, which
 * would otherwise find a fresh daemon that knows nothing and treat a sent report as missed.
 */
@Singleton
class PowerOffOutcomeCapture @Inject constructor(
    @ApplicationContext context: Context,
    private val helper: HelperClient,
) : HelperReplaceHook {

    internal var store: PowerOffStore = PrefsPowerOffStore(context)

    override suspend fun beforeReplace() {
        val last = helper.offReportStatus("")?.last ?: return
        store.putCaptured(last)
        Log.i(TAG, "offreport captured id=${last.id} state=${OffReportState.name(last.state)} before the daemon is replaced")
    }

    private companion object {
        const val TAG = "TgReport"
    }
}
