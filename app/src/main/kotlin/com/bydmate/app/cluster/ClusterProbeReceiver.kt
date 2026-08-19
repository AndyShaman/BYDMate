package com.bydmate.app.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Entry point of the UI7 cluster probe: `adb shell am broadcast -a com.bydmate.app.CLUSTER_PROBE
 * -p com.bydmate.app --es cmd <cmd> [--ei hold N] [--ei secs N] [--es items a,b] [--es what X]
 * [--ei v N]`.
 *
 * Every command is asynchronous (a hold runs for up to two minutes), so the receiver hands the
 * intent to [ClusterProbeRunner]'s own scope and returns at once: the process stays alive on
 * TrackingService, not on this receiver's window.
 */
class ClusterProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClusterProbeRunner.ACTION_PROBE) return
        ClusterProbeRunner.shared(context).handle(intent)
    }
}
