package com.bydmate.app.ui.settings

import android.content.Context
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.cluster.SteeringWheelKeyService
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.push.FidPushChannel
import com.bydmate.app.diagnostics.EndSnapshotSource
import com.bydmate.app.helper.push.FID_PUSH_OK
import com.bydmate.app.hud.HudController
import com.bydmate.app.hud.HudManeuverJournal
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.split.SplitJournal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The state at the end of a recording, appended after the logcat lines (audit item 1): the
 * header shows how things stood when the user pressed record, this shows how they stood when
 * the problem had happened. Only what the dump already gathers, and the journals only since
 * the recording began. Every part is caught on its own, like the header's.
 *
 * Only values already in memory: no helper or Binder read. A transact cannot be interrupted by
 * the recorder's timeout and holds the helper's lock that automation and HUD CAN writes wait on,
 * so what only a live read can tell (the car's HUD gate, the daemon's push table) prints `na`.
 */
@Singleton
class RecordingEndSnapshot @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val hudController: HudController,
    private val automationEngine: AutomationEngine,
    private val splitJournal: SplitJournal,
    private val fidPushChannel: FidPushChannel,
) : EndSnapshotSource {

    override suspend fun lines(sinceMs: Long): List<String> {
        val out = mutableListOf<String>()
        out += "timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}"
        part(out, "hud") {
            val diag = hudController.counters()
            listOf(
                "hud: status=${hudController.status.value} mode=${hudController.mode()} " +
                    "frames_sent=${diag?.framesSent ?: 0} last_frame_ts=${diag?.lastFrameTs ?: 0} " +
                    "last_rc=${diag?.lastRc ?: "n/a"} nonzero_rc=${diag?.nonZeroRcCount ?: 0} " +
                    "can_accepted=${diag?.canAccepted ?: 0} can_refused=${diag?.canRefused ?: 0} " +
                    "amap_frames=${diag?.amapFramesSent ?: 0} amap_stops=${diag?.amapStopsSent ?: 0}",
                "hud fid: gate=na (no live read at the end; each arm traces its gate)",
                "hub_snapshot=${RecordingDumpFormat.hubSnapshot(NavGuidanceHub.snapshot())}",
                "route_summary: ${NavGuidanceHub.routeSummary()}",
            )
        }
        part(out, "maneuvers") {
            val prefs = appContext.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE)
            tail("maneuvers", RecordingDumpFormat.since(HudManeuverJournal(prefs).lines(), sinceMs))
        }
        part(out, "cluster journal") {
            tail("cluster journal", RecordingDumpFormat.since(ClusterProjectionManager.journalLines(appContext), sinceMs))
        }
        part(out, "split journal") {
            tail("split journal", RecordingDumpFormat.since(splitJournal.read(), sinceMs))
        }
        part(out, "automation journal") {
            val rules = RecordingDumpFormat.since(automationEngine.journalDumpLines(), sinceMs)
            tail("automation journal", rules.map(RecordingDumpFormat::ruleLineById))
        }
        part(out, "fid push") { fidPushTotals().map { "fid push: $it" } }
        part(out, "keys") { listOf(SteeringWheelKeyService.keyCounters.line()) }
        return out
    }

    /** The dump's fid push totals from the app's own table: ok counts the subscribe replies; the
     *  daemon's later confirmations and its callback/delivery counters need a live read. */
    private fun fidPushTotals(): List<String> {
        val results = fidPushChannel.results
        if (results.isEmpty()) return listOf("no subscription")
        val ok = results.count { it.outcome == FID_PUSH_OK }
        return listOf(
            "subscribed=${results.size} ok=$ok failed=${results.size - ok}",
            "callback: na delivery: na",
            "resubscribes=${fidPushChannel.resubscribes}",
        )
    }

    private fun tail(title: String, lines: List<String>): List<String> =
        listOf("$title:") + lines.ifEmpty { listOf("(none)") }.map { "  $it" }

    @Suppress("TooGenericExceptionCaught") // a failing part must not cost the others
    private suspend fun part(out: MutableList<String>, name: String, block: suspend () -> List<String>) {
        try {
            out += block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            out += "($name failed: ${e.javaClass.simpleName})"
        }
    }
}
