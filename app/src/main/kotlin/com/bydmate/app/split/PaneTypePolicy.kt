package com.bydmate.app.split

import android.os.Build
import com.bydmate.app.helper.HelperBinderProtocol

/**
 * Decides how split PANES are typed on this firmware (#130).
 *
 * v3.9.1 retyped the panes from RECENTS to STANDARD so each pane gets its own root task and its
 * own input shield — accepted on-car on Leopard 3 (eng.build.20260106). On the newer
 * eng.build.20260320 firmware (Song L, Sea Lion 07) a STANDARD pane dies on the first tap: the
 * task finishes, the watchdog reports PaneClosed and opens the picker, and the split is unusable.
 * The same cars ran v3.9 fine with RECENTS panes (touch only on the top pane, caption suppressed
 * — a degradation, not a breakage).
 *
 * So STANDARD is opt-in per firmware and everything else falls back to RECENTS: an unknown
 * firmware degrades to the mode that has never been seen to kill a pane.
 *
 * Cluster projection is NOT covered here — it asks for RECENTS unconditionally
 * (ClusterProjectionManager).
 *
 * [fingerprint] is injectable so both branches can be pinned in tests; production reads
 * [Build.FINGERPRINT], which is a non-null platform field on a device but resolves to null under a
 * plain JVM unit test (same idiom as BatchReadGate).
 */
class PaneTypePolicy(fingerprint: String = Build.FINGERPRINT ?: "") {

    /** True when this firmware is on the STANDARD known-good list. */
    val knownGood: Boolean = KNOWN_GOOD_PATTERNS.any { it.containsMatchIn(fingerprint) }

    /** activityType handed to the daemon for pane launches / raises / mode flips. */
    val paneType: Int =
        if (knownGood) HelperBinderProtocol.PANE_TYPE_STANDARD else HelperBinderProtocol.PANE_TYPE_RECENTS

    /** Journal label: which type was chosen and which branch of the policy chose it. */
    val label: String = if (knownGood) "standard(known-good)" else "recents(fallback)"

    companion object {
        /** Firmware builds where STANDARD panes passed on-car acceptance. */
        val KNOWN_GOOD_BUILDS = listOf("eng.build.20260106")

        /**
         * A known-good id has to fill its whole build segment. A plain `contains` also accepts
         * "eng.build.202601060" — a different firmware whose id merely starts with an accepted
         * one, which would be opted into STANDARD panes untested. Real fingerprints carry a
         * timestamp after the id ("eng.build.20260106.201352"), so the segment ends at any
         * non-alphanumeric character or at the end of the string.
         */
        private val KNOWN_GOOD_PATTERNS =
            KNOWN_GOOD_BUILDS.map { Regex(Regex.escape(it) + """(?![0-9A-Za-z])""") }
    }
}
