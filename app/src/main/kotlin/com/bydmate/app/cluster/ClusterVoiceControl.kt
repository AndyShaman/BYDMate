package com.bydmate.app.cluster

import android.content.Context
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narrow voice-agent facade over cluster projection: read OUR projection state
 * (ClusterProjectionManager.currentMode) and drive it the same way the steering-wheel
 * star key does, only when the manual preconditions hold (Navi mode selected on the
 * cluster, Full mode in the Navigator app).
 */
@Singleton
class ClusterVoiceControl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helperClient: HelperClient,
    private val helperBootstrap: HelperBootstrap,
) {
    /** Success-honest state of OUR projection (a failed FULLSCREEN stays OFF). The native IPC
     *  fid is useless here: mode 4 also means BYD's own Chinese map is on the cluster, which
     *  made the tool claim «карта уже на приборке» while projecting nothing (APK 336). */
    fun projectionMode(): ClusterMode = ClusterProjectionManager.currentMode

    /** Label of the user-selected projection app (settings row cache), for spoken replies. */
    fun projectedAppLabel(): String? =
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(ClusterProjectionManager.KEY_TARGET_LABEL, null)

    fun apply(on: Boolean) = ClusterProjectionManager.setMode(
        context, if (on) ClusterMode.FULLSCREEN else ClusterMode.OFF,
        helperClient, helperBootstrap)

    /** Why the last FULLSCREEN attempt failed; null after success/OFF. See [ClusterProjectionManager.lastFailure]. */
    fun lastFailure(): String? = ClusterProjectionManager.lastFailure
}
