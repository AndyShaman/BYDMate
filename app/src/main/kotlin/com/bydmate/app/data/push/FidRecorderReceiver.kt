package com.bydmate.app.data.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.cluster.ClusterEntryPoint
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.push.fidRecorderStatusLines
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Entry point of the diagnostic fid recorder: `adb shell am broadcast -a com.bydmate.app.FID_RECORD
 * -p com.bydmate.app --es cmd start|stop|status [--es devs 1001,1004]` (scripts/fid-record.sh).
 *
 * The answer goes to logcat under the `FidRec` tag — the same tag the recorder's own lines and the
 * in-app log recorder use, so one `logcat -s FidRec` follows the whole run.
 *
 * The receiver stays exported (the manifest is shared), so it is gated to `-test` builds plus local
 * debug builds exactly like [com.bydmate.app.cluster.ClusterProbeReceiver]: a public build ignores
 * the broadcast.
 */
class FidRecorderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RECORD) return
        if (!fidRecorderEnabled(BuildConfig.VERSION_NAME, BuildConfig.DEBUG)) return
        val cmd = intent.getStringExtra(EXTRA_CMD).orEmpty()
        val devices = parseDevices(intent.getStringExtra(EXTRA_DEVICES))
        val helper = EntryPointAccessors
            .fromApplication(context.applicationContext, ClusterEntryPoint::class.java)
            .helperClient()
        // A full start is dozens of vendor registrations, so the work leaves the receiver window;
        // the process stays alive on TrackingService, as with the cluster probe.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { run(helper, cmd, devices) }
                .onFailure { Log.w(TAG, "rec $cmd failed: $it") }
            pending.finish()
        }
    }

    private suspend fun run(helper: HelperClient, cmd: String, devices: IntArray) {
        when (cmd) {
            CMD_START -> {
                val started = helper.recStart(devices)
                Log.i(
                    TAG,
                    if (started == null) {
                        "rec start: daemon unreachable"
                    } else {
                        "rec start: devices=${started.devices} registered=${started.registered}"
                    }
                )
                report(helper)
            }

            CMD_STOP -> Log.i(TAG, "rec stop: ${if (helper.recStop()) "ok" else "daemon unreachable"}")

            CMD_STATUS -> report(helper)

            else -> Log.i(TAG, "rec: unknown cmd '$cmd' (start|stop|status)")
        }
    }

    private suspend fun report(helper: HelperClient) {
        val status = helper.recStatus()
        if (status == null) {
            Log.i(TAG, "rec status: daemon unreachable")
            return
        }
        fidRecorderStatusLines(status).forEach { Log.i(TAG, it) }
    }

    /** "1001,1004" -> [1001, 1004]; anything unparsable is dropped, empty means every device. */
    private fun parseDevices(raw: String?): IntArray =
        raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()

    companion object {
        const val ACTION_RECORD = "com.bydmate.app.FID_RECORD"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_DEVICES = "devs"
        const val CMD_START = "start"
        const val CMD_STOP = "stop"
        const val CMD_STATUS = "status"

        private const val TAG = "FidRec"
    }
}

/** The recorder answers on `-test` builds and on local debug builds; a public APK ignores it. */
internal fun fidRecorderEnabled(versionName: String, debug: Boolean): Boolean =
    versionName.endsWith("-test") || debug
