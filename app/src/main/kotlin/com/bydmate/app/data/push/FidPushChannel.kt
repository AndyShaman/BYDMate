package com.bydmate.app.data.push

import android.os.Binder
import android.os.Parcel
import android.util.Log
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.helper.push.FID_PUSH_OK
import com.bydmate.app.helper.push.FidPushEvent
import com.bydmate.app.helper.push.FidPushResult
import com.bydmate.app.helper.push.FidPushStatus
import com.bydmate.app.helper.push.FidPushSub
import com.bydmate.app.helper.push.readPushEvents
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App end of the fid push channel: hands the daemon a callback binder, receives the events the
 * vendor listeners produce, lays them into the live snapshot and republishes them as a flow for
 * consumers that must react faster than the poll (the blind-spot loop).
 *
 * The poll is untouched and stays the source of truth — a push only moves a value forward between
 * two ticks, and a snapshot that does not exist yet is never invented.
 */
@Singleton
class FidPushChannel @Inject constructor(
    private val helper: HelperClient,
) {

    private val _events = MutableSharedFlow<FidPushEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<FidPushEvent> = _events.asSharedFlow()

    /**
     * The binder the daemon pushes into. Never registered anywhere: the daemon only ever gets it
     * through TX_PUSH_SUBSCRIBE, and the interface token keeps anything else out.
     */
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != HelperBinderProtocol.TX_PUSH_EVENT) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(HelperBinderProtocol.PUSH_CALLBACK_DESCRIPTOR)
            // One transact carries a whole flush of the daemon's buffer; consumers still see
            // one event at a time.
            val now = System.currentTimeMillis()
            for (event in readPushEvents(data, now)) _events.tryEmit(event)
            return true
        }
    }

    /** Last outcome table the daemon reported; empty = no subscription in force. */
    @Volatile var results: List<FidPushResult> = emptyList()
        private set

    /** How often the subscription was (re)installed — a rising number means a restarting daemon. */
    @Volatile var resubscribes: Int = 0
        private set

    /** elapsedRealtime of the last installed subscription; 0 = none in force. */
    @Volatile private var subscribedAtElapsed: Long = 0L

    /** fid → FidMap field, for the address table the current subscription was built from. */
    @Volatile private var fieldByFid: Map<Int, String> = emptyMap()

    /** FidMap field the fid belongs to in the subscription in force, or null when it is not ours. */
    fun fieldFor(fid: Int): String? = fieldByFid[fid]

    /**
     * (Re)installs the subscription with the fid addresses in force right now. Called whenever the
     * daemon became available or the firmware catalog moved the addresses — both are rare, and the
     * daemon side treats a repeat as a clean reinstall, so there is no state to keep in step here.
     */
    suspend fun resubscribe(reason: String) {
        val fields = FidPushApplier.PUSH_FIELDS
        val subs = fields.map { FidPushSub(FidAddresses.fid(it), FidAddresses.device(it)) }
        val table = helper.pushSubscribe(callback, subs)
        if (table == null) {
            results = emptyList()
            fieldByFid = emptyMap()
            Log.w(TAG, "resubscribe ($reason): daemon unreachable")
            return
        }
        results = table
        fieldByFid = fields.indices.associate { subs[it].fid to fields[it] }
        subscribedAtElapsed = android.os.SystemClock.elapsedRealtime()
        resubscribes++
        val ok = table.count { it.outcome == FID_PUSH_OK }
        Log.i(TAG, "resubscribe ($reason): ok=$ok failed=${table.size - ok}")
    }

    /** The `--- fid push ---` section of the diagnostic dump. */
    suspend fun diagnosticsSnapshot(): List<String> {
        val local = results
        if (local.isEmpty()) return listOf("no subscription")
        val status: FidPushStatus? = helper.pushStatus()
        val rows = status?.rows.orEmpty().associateBy { it.fid }
        // The daemon re-reports outcomes: a `sent` row confirmed by its first event reads OK there.
        val outcomes = local.associate { it.fid to (rows[it.fid]?.outcome ?: it.outcome) }
        val ok = outcomes.values.count { it == FID_PUSH_OK }
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val lines = mutableListOf(
            "subscribed=${local.size} ok=$ok failed=${local.size - ok}"
        )
        for (result in local) {
            val field = fieldByFid[result.fid] ?: "?"
            val row = rows[result.fid]
            val age = row?.lastTsElapsed?.takeIf { it > 0 }?.let { (nowElapsed - it) / 1000 }
            lines += "$field ${result.fid} dev=${result.device} ${outcomes[result.fid]} " +
                "events=${row?.events ?: 0} last=${row?.lastIntValue ?: "-"} age=${age ?: "-"}s"
        }
        lines += if (status == null) {
            "callback: status unavailable (daemon unreachable)"
        } else {
            "callback: ${if (status.callbackAlive) "alive" else "dead"} deliver errors=${status.deliverErrors}"
        }
        if (status != null) {
            lines += "delivery: packets=${status.packets} events=${status.events} coalesced=${status.coalesced}"
        }
        lines += "resubscribes=$resubscribes"
        // Subscribed, accepted, silent — while the poll keeps reading the field. The one
        // shape a "push is live" verdict cannot be read off the table above.
        lines += FidPushDiagnostics.pollVsPushLines(
            rows = pollPushRows(local, outcomes, rows),
            sinceSubscribeMs = if (subscribedAtElapsed == 0L) 0L else nowElapsed - subscribedAtElapsed,
        )
        return lines
    }

    private fun pollPushRows(
        local: List<FidPushResult>,
        outcomes: Map<Int, String>,
        rows: Map<Int, com.bydmate.app.helper.push.FidPushStatusRow>,
    ): List<FidPushDiagnostics.PollPushRow> {
        val polled = com.bydmate.app.data.nativestack.PollFieldValues.latest()
        return local.map { result ->
            val field = fieldByFid[result.fid] ?: "?"
            FidPushDiagnostics.PollPushRow(
                field = field,
                fid = result.fid,
                subscribed = outcomes[result.fid] == FID_PUSH_OK,
                events = rows[result.fid]?.events ?: 0,
                pollValue = polled[field],
            )
        }
    }

    private companion object {
        const val TAG = "FidPush"

        /** Room for a few whole flushes (one event per subscribed fid each) before the collector runs. */
        const val EVENT_BUFFER = 256
    }
}
