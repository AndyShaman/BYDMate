package com.bydmate.app.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bydmate.app.BuildConfig

/**
 * Entry point of the UI7 cluster probe: `adb shell am broadcast -a com.bydmate.app.CLUSTER_PROBE
 * -p com.bydmate.app --es cmd <cmd> [--ei hold N] [--ei secs N] [--es items a,b] [--es what X]
 * [--ei v N]`.
 *
 * Every command is asynchronous (a hold runs for up to two minutes), so the receiver hands the
 * intent to [ClusterProbeRunner]'s own scope and returns at once: the process stays alive on
 * TrackingService, not on this receiver's window.
 *
 * The receiver stays exported (the manifest is shared), so it is gated to `-test` builds the
 * same way as FidSubscriptionManager (plus local debug builds): a public build ignores the broadcast.
 */
class ClusterProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ClusterProbeRunner.ACTION_PROBE) return
        if (!BuildConfig.VERSION_NAME.endsWith("-test") && !BuildConfig.DEBUG) return
        ClusterProbeRunner.shared(context).handle(intent)
    }
}
