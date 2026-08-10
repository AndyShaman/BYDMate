package com.bydmate.app.split

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterProjectionManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The verdict must latch on exactly one signature — unavailable at boot N, still unavailable at a
 * later boot with the reboot hint armed the whole time — and stay inert on everything else. A
 * false latch permanently hides split-screen from a head unit that only needed its reboot (#139).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitFreeformVerdictTest {

    private fun prefs() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("split_verdict_test", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }

    private fun android.content.SharedPreferences.armRebootPending() {
        edit().putBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, true).commit()
    }

    private fun android.content.SharedPreferences.armClusterRebootPending() {
        edit().putBoolean(ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING, true).commit()
    }

    @Test fun `repeated failures within one boot do not latch`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { 7 }, fingerprint = { "fp" })

        repeat(5) { assertFalse(verdict.noteUnavailable()) }

        assertFalse("one boot is never proof — the reboot has not happened yet", verdict.unsupported())
        assertTrue(sharedPrefs.getInt(SplitFreeformVerdict.KEY_SEEN_BOOT, 0) == 7)
    }

    @Test fun `unavailable again at a later boot latches the verdict`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })

        assertFalse(verdict.noteUnavailable())
        assertFalse(verdict.unsupported())

        boot = 8
        assertTrue("the latching call reports it so the caller can journal it", verdict.noteUnavailable())
        assertTrue(verdict.unsupported())
        assertTrue(
            "the latch must survive a process restart",
            SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" }).unsupported(),
        )
    }

    @Test fun `without the reboot hint armed nothing is remembered`() {
        val sharedPrefs = prefs()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })

        assertFalse(verdict.noteUnavailable())
        boot = 8
        assertFalse(verdict.noteUnavailable())

        assertFalse(verdict.unsupported())
    }

    /**
     * A seen-marker from a previous cycle must not pair with a freshly armed hint. Nothing runs
     * while the feature is disabled, so the marker can only be dropped at disarm time — production
     * does exactly this in ClusterProjectionManager.clearSplitRebootHint.
     */
    @Test fun `a disarm clears the seen marker so the next cycle starts fresh`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })
        assertFalse(verdict.noteUnavailable())
        assertTrue(sharedPrefs.getInt(SplitFreeformVerdict.KEY_SEEN_BOOT, 0) == 7)

        // User turns split off: the hint is disarmed and the marker goes with it.
        sharedPrefs.edit()
            .putBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, false).commit()
        SplitFreeformVerdict.clearSeenMarker(sharedPrefs)
        assertFalse(sharedPrefs.contains(SplitFreeformVerdict.KEY_SEEN_BOOT))

        // A real boot happens while the feature is off, then the user re-enables it: the hint is
        // armed fresh and ITS reboot has not happened yet.
        boot = 8
        sharedPrefs.armRebootPending()
        assertFalse("the first failure of a fresh cycle is not proof", verdict.noteUnavailable())
        assertFalse(verdict.unsupported())
        assertTrue(sharedPrefs.getInt(SplitFreeformVerdict.KEY_SEEN_BOOT, 0) == 8)

        boot = 9
        assertTrue("only the fresh cycle's own second boot latches", verdict.noteUnavailable())
        assertTrue(verdict.unsupported())
    }

    /**
     * Both hints describe the same firmware fact, so disabling ONE feature interrupts nothing: the
     * other cycle is still running with its hint armed, and its proof must survive. Dropping the
     * shared marker here would cost the user one more pointless reboot before the latch.
     */
    @Test fun `a disarm with the sibling hint still armed keeps the marker`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        sharedPrefs.armClusterRebootPending()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })
        assertFalse(verdict.noteUnavailable())

        // Split is turned off; direct projection stays on, so its cycle is untouched.
        sharedPrefs.edit()
            .putBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, false).commit()
        SplitFreeformVerdict.clearSeenMarker(sharedPrefs)
        assertTrue(
            "the surviving cluster cycle keeps its proof",
            sharedPrefs.getInt(SplitFreeformVerdict.KEY_SEEN_BOOT, 0) == 7,
        )

        boot = 8
        assertTrue("the reboot the cluster hint asked for has happened", verdict.noteUnavailable())
        assertTrue(verdict.unsupported())
    }

    @Test fun `only disarming the last armed hint clears the marker`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        sharedPrefs.armClusterRebootPending()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })
        assertFalse(verdict.noteUnavailable())

        sharedPrefs.edit()
            .putBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, false).commit()
        SplitFreeformVerdict.clearSeenMarker(sharedPrefs)
        sharedPrefs.edit()
            .putBoolean(ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING, false).commit()
        SplitFreeformVerdict.clearSeenMarker(sharedPrefs)
        assertFalse(
            "no cycle is left to own the marker",
            sharedPrefs.contains(SplitFreeformVerdict.KEY_SEEN_BOOT),
        )

        // A real boot passes with both features off, then the user re-enables direct projection.
        boot = 8
        sharedPrefs.armClusterRebootPending()
        assertFalse("the fresh cycle's own reboot has not happened yet", verdict.noteUnavailable())
        assertFalse(verdict.unsupported())
    }

    /** The cluster hint alone is the whole cycle when split is off but direct projection is on. */
    @Test fun `the cluster reboot hint alone is enough to latch`() {
        val sharedPrefs = prefs()
        sharedPrefs.armClusterRebootPending()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })

        assertFalse(verdict.noteUnavailable())
        assertTrue(sharedPrefs.getInt(SplitFreeformVerdict.KEY_SEEN_BOOT, 0) == 7)
        assertFalse(verdict.unsupported())

        boot = 8
        assertTrue(verdict.noteUnavailable())
        assertTrue(verdict.unsupported())
    }

    @Test fun `both hints disarmed clears a marker left by the cluster cycle`() {
        val sharedPrefs = prefs()
        sharedPrefs.armClusterRebootPending()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })
        assertFalse(verdict.noteUnavailable())

        sharedPrefs.edit()
            .putBoolean(ClusterProjectionManager.KEY_FREEFORM_REBOOT_PENDING, false).commit()
        boot = 8
        assertFalse(verdict.noteUnavailable())
        assertFalse(sharedPrefs.contains(SplitFreeformVerdict.KEY_SEEN_BOOT))

        sharedPrefs.armClusterRebootPending()
        assertFalse("the stale marker must be gone", verdict.noteUnavailable())
        assertFalse(verdict.unsupported())
    }

    @Test fun `an unreadable boot count keeps the verdict inert`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { -1 }, fingerprint = { "fp" })

        repeat(3) { assertFalse(verdict.noteUnavailable()) }

        assertFalse(verdict.unsupported())
        assertFalse(sharedPrefs.contains(SplitFreeformVerdict.KEY_SEEN_BOOT))
    }

    @Test fun `a firmware update drops the verdict`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        var boot = 7
        val old = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp-old" })
        old.noteUnavailable()
        boot = 8
        assertTrue(old.noteUnavailable())

        val afterOta = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp-new" })
        assertFalse("an OTA may add freeform support", afterOta.unsupported())

        // The reset also wipes the marker, so the OTA build has to collect its own proof.
        assertFalse(afterOta.noteUnavailable())
        assertFalse(sharedPrefs.getBoolean(SplitFreeformVerdict.KEY_UNSUPPORTED, false))
    }

    @Test fun `a successful start clears the latch`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })
        verdict.noteUnavailable()
        boot = 8
        assertTrue(verdict.noteUnavailable())
        assertTrue(verdict.unsupported())

        verdict.clearOnSuccess()

        assertFalse(verdict.unsupported())
        assertFalse(sharedPrefs.contains(SplitFreeformVerdict.KEY_SEEN_BOOT))
    }

    /**
     * Without this retry the latch is irreversible: both entry points bail before any attempt, so
     * [SplitFreeformVerdict.clearOnSuccess] can never be reached and a false latch survives until
     * an OTA changes the fingerprint.
     */
    @Test fun `a latched verdict still lets one start per boot through`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })
        verdict.noteUnavailable()
        boot = 8
        assertTrue(verdict.noteUnavailable())

        assertFalse("the first start of this boot must reach the firmware", verdict.suppressStart())
        assertTrue(verdict.suppressStart())
        assertTrue(verdict.suppressStart())
        assertTrue("the hint text stays honest while the retry is in flight", verdict.unsupported())

        boot = 9
        assertFalse("a real boot re-arms the single retry", verdict.suppressStart())
        assertTrue(verdict.suppressStart())

        assertTrue(
            "a fresh process must see the retry as already spent",
            SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })
                .suppressStart(),
        )
    }

    @Test fun `a retry that succeeds unlatches the verdict for good`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })
        verdict.noteUnavailable()
        boot = 8
        assertTrue(verdict.noteUnavailable())
        assertFalse(verdict.suppressStart())

        verdict.clearOnSuccess()

        repeat(3) { assertFalse("no latch left to suppress on", verdict.suppressStart()) }
        assertFalse(sharedPrefs.contains(SplitFreeformVerdict.KEY_RETRY_BOOT))
    }

    /**
     * The latch signature is only evidence on firmware we have never seen freeform work on. On
     * eng.build.20260106 it has been running for releases, so the same signature means the reboot
     * proof is wrong (a quickboot bumped BOOT_COUNT), not that the firmware ignores the flag (#147).
     */
    @Test fun `the latch signature never latches on a known-good firmware`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { KNOWN_GOOD_FP })

        assertFalse(verdict.noteUnavailable())
        boot = 8
        assertFalse("freeform is proven on this build", verdict.noteUnavailable())

        assertFalse(verdict.unsupported())
        assertFalse(sharedPrefs.getBoolean(SplitFreeformVerdict.KEY_UNSUPPORTED, false))
        assertFalse(verdict.suppressStart())
    }

    /** #147: the field unit already carries a latch written by an older build. */
    @Test fun `a latch already written on a known-good firmware stops being honoured`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        var boot = 7
        val latched = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { KNOWN_GOOD_FP })
        // Written the way the old build did: the gate did not exist, so the signature latched.
        sharedPrefs.edit()
            .putInt(SplitFreeformVerdict.KEY_SCHEMA, SplitFreeformVerdict.SCHEMA_VERSION)
            .putString(SplitFreeformVerdict.KEY_FP, KNOWN_GOOD_FP)
            .putBoolean(SplitFreeformVerdict.KEY_UNSUPPORTED, true)
            .commit()

        assertFalse("the hint text must stop calling a working firmware unsupported", latched.unsupported())
        repeat(3) { assertFalse("every start reaches the firmware", latched.suppressStart()) }

        // The next unavailable outcome also scrubs the stale flag out of the prefs.
        boot = 8
        assertFalse(latched.noteUnavailable())
        assertFalse(sharedPrefs.getBoolean(SplitFreeformVerdict.KEY_UNSUPPORTED, false))
    }

    @Test fun `a latch with an unreadable boot count keeps suppressing`() {
        val sharedPrefs = prefs()
        sharedPrefs.armRebootPending()
        var boot = 7
        val verdict = SplitFreeformVerdict(sharedPrefs, bootCount = { boot }, fingerprint = { "fp" })
        verdict.noteUnavailable()
        boot = 8
        assertTrue(verdict.noteUnavailable())

        boot = -1
        repeat(3) { assertTrue("no way to tell one boot from the next", verdict.suppressStart()) }
    }

    private companion object {
        /** The acceptance build of [PaneTypePolicy.KNOWN_GOOD_BUILDS], as the field unit reports it. */
        const val KNOWN_GOOD_FP =
            "BYD-AUTO/DiLink5.0/DiLink5.0:12/SP1A.210812.016/eng.build.20260106.201352:user/release-keys"
    }
}
