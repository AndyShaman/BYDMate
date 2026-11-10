package com.bydmate.app.ui.widget

import android.content.Context
import android.util.Log
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.split.SplitOverlayController
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.ui.overlay.OverlayNotificationManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import com.bydmate.app.data.nativestack.VehicleKindMonitor
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

/**
 * Hilt entry point used by [WidgetController] (a singleton object, not Hilt-managed)
 * to reach the split-screen singletons without constructor injection.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SplitWidgetEntryPoint {
    fun splitSessionManager(): SplitSessionManager
    fun splitOverlayController(): SplitOverlayController
    fun helperBootstrap(): HelperBootstrap
    fun vehicleKindMonitor(): VehicleKindMonitor
}

/**
 * A person's own split gesture: the widget's left tap or the split steering key. [WidgetController.runSplitTap]
 * toggle; no saved pair opens the first-pair picker; errors go to the overlay, in the language
 * of [context] (the caller passes a context localized to the app language).
 */
@Suppress("TooGenericExceptionCaught") // callers' scopes have no exception handler
internal suspend fun toggleSplitByUser(context: Context, fromWidget: Boolean) {
    try {
        val ep = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SplitWidgetEntryPoint::class.java,
        )
        val manager = ep.splitSessionManager()
        WidgetController.runSplitTap(
            manager = manager,
            onNoPair = { ep.splitOverlayController().showFirstPairPicker() },
            onError = { res ->
                OverlayNotificationManager.show(
                    context.applicationContext,
                    context.getString(res),
                    "",
                )
            },
            adbBlocked = {
                // Only a daemon that is dead RIGHT NOW makes the recorded reason
                // relevant: a later successful spawn leaves the old record in place
                // for the dump, but isHealthy() then keeps it out of the UI.
                val bootstrap = ep.helperBootstrap()
                !bootstrap.isHealthy() && bootstrap.lastSpawnFailure()?.reason ==
                    HelperBootstrap.SpawnFailReason.ADB_UNREACHABLE
            },
            alwaysStart = WidgetController.splitAlwaysStart(fromWidget, manager.isNativePanesFirmware()),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("WidgetController", "split toggle failed (widget=$fromWidget): ${e.message}")
    }
}
