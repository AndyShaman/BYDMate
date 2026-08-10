package com.bydmate.app.split

import android.content.SharedPreferences
import android.os.Build
import com.bydmate.app.cluster.ClusterProjectionManager

/**
 * Remembers that this firmware ignores the freeform flag, so split-screen and cluster direct
 * projection stop retrying (#139).
 *
 * On DiLink 5.0 a FREEFORM_UNAVAILABLE result means the head unit has not picked up
 * `enable_freeform_support` yet and a reboot fixes it — which is what the settings hint says.
 * On some 5.1 / Android 13 firmwares (Denza N9 and one field tester) the flag is never honoured:
 * the hint tells the user to reboot, the reboot changes nothing, and every retry force-stops and
 * relaunches both pane apps for nothing.
 *
 * The two cases are told apart by [Settings.Global.BOOT_COUNT][android.provider.Settings.Global.BOOT_COUNT],
 * which the framework increments on a real boot only — DiLink's quickboot/suspend resume does not
 * touch it, so a bumped counter is proof that the machine actually went through a cold start.
 * Seeing freeform unavailable at boot N and again at a later boot, with a reboot hint armed the
 * whole time, means the reboot the user was asked for has happened and did not help. Only then is
 * the verdict latched. Either hint counts — [ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING]
 * and [ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING] describe the same firmware fact from
 * the split and the cluster side, and both features ride on the same freeform flag.
 *
 * The latch is guarded like [com.bydmate.app.data.vehicle.WindowChannelStorePrefs]: a schema bump
 * or a [Build.FINGERPRINT] change (OTA — the firmware may have gained freeform support) discards
 * it. [clearOnSuccess] is the other escape hatch: freeform working once disproves the verdict
 * whatever the reason it was latched — and [suppressStart] lets one attempt per real boot through
 * so that escape hatch stays reachable.
 *
 * On the builds of [PaneTypePolicy.isKnownGood] the verdict is inert entirely: we have run freeform
 * split on those firmwares ourselves, so "this firmware ignores the flag" is false by construction
 * and any latch there is a misread of the boot counter — a field report on eng.build.20260106 showed
 * both "unavailable" samples 73 s apart, i.e. a quickboot that bumped BOOT_COUNT without the cold
 * start the hint asked for (#147). Nothing latches there, and a latch an older build already wrote
 * stops being honoured. The reboot hint itself is untouched: the flag really is off until the head
 * unit picks it up, and the user still has to reboot for it.
 *
 * @param prefs     [ClusterProjectionManager.PREFS_NAME] — the same file that holds the reboot hint.
 * @param bootCount current BOOT_COUNT, -1 (or 0) when unreadable; the verdict stays inert then.
 */
class SplitFreeformVerdict(
    private val prefs: SharedPreferences,
    private val bootCount: () -> Int,
    // Build.FINGERPRINT is non-null on a device but null under a plain JVM unit test.
    private val fingerprint: () -> String = { Build.FINGERPRINT ?: "" },
) {

    /** True once this firmware is proven to ignore the freeform flag. */
    fun unsupported(): Boolean = synchronized(LOCK) {
        !knownGood() && guardsPass() && prefs.getBoolean(KEY_UNSUPPORTED, false)
    }

    /**
     * Whether this start must be suppressed: [unsupported], except for ONE live retry per real
     * boot.
     *
     * That retry is what makes a false latch self-healing. Without it both entry points bail
     * before any attempt, [clearOnSuccess] becomes unreachable, and a verdict latched by mistake
     * hides the feature until an OTA changes the fingerprint. On firmware that works the attempt
     * reaches [clearOnSuccess] and the latch is gone for good; on firmware that really ignores the
     * flag it is a daemon probe that returns UNAVAILABLE, and neither entry point kills the pane
     * apps to run it (while the verdict is latched the split path skips both its pre-launch
     * force-stop and the relaunch fallback — SplitSessionManager.forceStopIfNeeded and
     * relaunchPaneLocked).
     *
     * Hint-text callers keep using [unsupported] — the UI must stay honest while the retry is in
     * flight, and it flips on its own once the retry succeeds.
     *
     * With BOOT_COUNT unreadable a latch present can only have been written while it WAS readable,
     * so there is no way to tell one boot from the next and the suppression stands.
     */
    fun suppressStart(): Boolean {
        return synchronized(LOCK) {
            if (!unsupported()) return false
            val boot = bootCount()
            if (boot <= 0) return true
            if (prefs.getInt(KEY_RETRY_BOOT, 0) == boot) return true
            // commit(), not apply(): the attempt this stamp pays for starts immediately, and a
            // process killed before an async write reached disk would grant a second one in the
            // same boot. At most one write per start attempt on a tiny prefs file. A write that
            // did not land fails closed for the same reason: no retry without a durable stamp.
            if (!stamped().putInt(KEY_RETRY_BOOT, boot).commit()) return true
            false
        }
    }

    /**
     * Records one FREEFORM_UNAVAILABLE outcome. Returns true when THIS call latched the verdict,
     * so the caller can journal the transition once instead of on every failed start.
     */
    fun noteUnavailable(): Boolean {
        return synchronized(LOCK) {
            if (!guardsPass()) reset()
            // Freeform is proven to work on this build, so an unavailable result describes this
            // boot, not the firmware. Clear what an older build may have latched here so the
            // diagnostic dump shows the state the verdict actually acts on.
            if (knownGood()) {
                if (prefs.contains(KEY_UNSUPPORTED) || prefs.contains(KEY_SEEN_BOOT)) {
                    stamped().remove(KEY_UNSUPPORTED).remove(KEY_SEEN_BOOT).apply()
                }
                return false
            }
            val boot = bootCount()
            // Unknown boot count: no proof is obtainable, so nothing is recorded either.
            if (boot <= 0) return false
            // A hint must have been armed for the whole N → N+1 span. When neither is armed the
            // "reboot and retry" cycle is not running (the user toggled the feature off, which
            // re-arms a fresh hint on the next enable), and a marker left from before must not be
            // paired with it.
            if (!rebootHintArmed()) {
                if (prefs.contains(KEY_SEEN_BOOT)) stamped().remove(KEY_SEEN_BOOT).apply()
                return false
            }
            val seen = prefs.getInt(KEY_SEEN_BOOT, 0)
            if (seen in 1 until boot) {
                stamped().putBoolean(KEY_UNSUPPORTED, true).remove(KEY_SEEN_BOOT).apply()
                return true
            }
            if (seen != boot) stamped().putInt(KEY_SEEN_BOOT, boot).apply()
            false
        }
    }

    /** Freeform actually worked: drop the latch, the pending marker and the retry stamp. */
    fun clearOnSuccess() = synchronized(LOCK) {
        prefs.edit().remove(KEY_SEEN_BOOT).remove(KEY_UNSUPPORTED).remove(KEY_RETRY_BOOT).apply()
    }

    private fun knownGood(): Boolean = PaneTypePolicy.isKnownGood(fingerprint())

    private fun rebootHintArmed(): Boolean =
        prefs.getBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, false) ||
            prefs.getBoolean(ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING, false)

    private fun guardsPass(): Boolean =
        prefs.getInt(KEY_SCHEMA, -1) == SCHEMA_VERSION && prefs.getString(KEY_FP, "") == fingerprint()

    /** Drops everything decided under the old schema / firmware and stamps the current guards. */
    private fun reset() {
        stamped().remove(KEY_SEEN_BOOT).remove(KEY_UNSUPPORTED).remove(KEY_RETRY_BOOT).apply()
    }

    private fun stamped(): SharedPreferences.Editor =
        prefs.edit().putInt(KEY_SCHEMA, SCHEMA_VERSION).putString(KEY_FP, fingerprint())

    companion object {
        /** Bump when the latch criteria change, to discard verdicts decided under the old rule. */
        const val SCHEMA_VERSION = 1
        const val KEY_SCHEMA = "split_ff_verdict_schema"
        const val KEY_FP = "split_ff_verdict_fp"
        const val KEY_SEEN_BOOT = "split_ff_seen_boot"
        const val KEY_UNSUPPORTED = "split_ff_unsupported"
        /** Boot in which the once-per-boot retry of [suppressStart] was already spent. */
        const val KEY_RETRY_BOOT = "split_ff_retry_boot"

        // The split session and the cluster projection each build their own instance over this
        // same prefs file and call it from different coroutines. Every mutator is a read-modify-
        // write, so without a process-wide lock a failing start could latch AFTER a concurrent
        // success cleared the latch. Class-level: it covers all instances in the process, and the
        // helper daemon never touches these prefs.
        private val LOCK = Any()

        /**
         * Drops the seen-marker, called when a reboot hint is DISARMED — but only when it was the
         * LAST armed one. The caller must have written its own flag first.
         *
         * The latch signature is "unavailable at boot N and again later with a hint armed the
         * whole span". Disabling the feature interrupts that span without going through
         * [noteUnavailable] (nothing runs while the feature is off), so a marker from before the
         * disable would otherwise survive a real boot and pair with the freshly armed hint of the
         * next enable — latching on the very first attempt of a cycle whose own reboot has not
         * happened yet.
         *
         * The two hints describe the same firmware fact, and [noteUnavailable] accepts either one
         * as the armed span. So while the sibling hint is still armed nothing was interrupted: its
         * cycle keeps running and keeps its proof. Wiping the marker there would cost the user one
         * more pointless reboot before the latch.
         */
        fun clearSeenMarker(prefs: SharedPreferences) = synchronized(LOCK) {
            val stillArmed =
                prefs.getBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, false) ||
                    prefs.getBoolean(ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING, false)
            if (!stillArmed) prefs.edit().remove(KEY_SEEN_BOOT).apply()
        }
    }
}
