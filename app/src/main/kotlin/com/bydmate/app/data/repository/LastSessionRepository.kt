package com.bydmate.app.data.repository

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight in-memory holder for the most recently completed driving session's
 * SOC bookmarks. Written by TrackingService on session start/end; read by
 * HistoryImporter when enriching freshly-imported TripEntity rows.
 *
 * In-memory only — intentionally not persisted. Process restart mid-session
 * drops the bookmarks; HistoryImporter falls back to null socStart/socEnd for
 * that trip (same as before the native-stack migration). This is acceptable:
 * the SOC enrichment is best-effort and only benefits the current session.
 *
 * Thread safety: single AtomicReference<Snapshot?> guarantees that snapshot()
 * always returns a consistent view — no mix of fields from different sessions.
 */
@Singleton
class LastSessionRepository @Inject constructor() {

    data class Snapshot(
        val startSoc: Int?,
        val endSoc: Int?,
        val startTs: Long?,
        val endTs: Long?,
    )

    private val state = AtomicReference<Snapshot?>(null)

    fun onSessionStart(soc: Int?, ts: Long) {
        state.set(Snapshot(startSoc = soc, endSoc = null, startTs = ts, endTs = null))
    }

    fun onSessionEnd(soc: Int?, ts: Long) {
        // CAS loop: preserve start fields written by onSessionStart.
        while (true) {
            val cur = state.get()
            val next = Snapshot(
                startSoc = cur?.startSoc,
                endSoc   = soc,
                startTs  = cur?.startTs,
                endTs    = ts,
            )
            if (state.compareAndSet(cur, next)) return
        }
    }

    fun snapshot(): Snapshot? = state.get()
}
