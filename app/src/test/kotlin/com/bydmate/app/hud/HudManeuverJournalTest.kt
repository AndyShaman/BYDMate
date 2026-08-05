package com.bydmate.app.hud

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HudManeuverJournalTest {

    private class FakeSink : HudEventSink {
        override fun fireEvent(topic: Long, payload: ByteArray): Int = 0
    }

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("hud_journal_test", Context.MODE_PRIVATE)
    private var clock = 1_700_000_000_000L

    @Before fun clear() {
        prefs.edit().clear().commit()
        NavGuidanceHub.reset()
    }

    private fun journal() = HudManeuverJournal(prefs) { clock }

    private fun installAmapService() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.byd.amapservice" })
    }

    private fun guidance(gaode: Int, distance: Int = 250) = NavGuidanceHub.update(
        NavGuidance(maneuverGaode = gaode, distanceMeters = distance, road = "A", speedLimit = 0),
        NavGuidanceHub.Source.A11Y, nowMs = clock,
    )

    @Test fun `line carries both channels and the suppression flag`() {
        journal().append(
            maneuverGaode = 13, distanceMeters = 400, f28 = 0,
            amapIcon = 11, roundaboutNum = null, suppressArrow = false,
        )
        assertTrue(
            journal().lines().single()
                .endsWith("gaode=13 dist=400m f28=0 amap=11 suppress=false"))
    }

    @Test fun `numbered roundabout exit is recorded next to the icon`() {
        journal().append(
            maneuverGaode = 27, distanceMeters = 120, f28 = 0,
            amapIcon = 12, roundaboutNum = 3, suppressArrow = false,
        )
        assertTrue(journal().lines().single().endsWith("f28=0 amap=12 rab=3 suppress=false"))
    }

    @Test fun `a silent amap channel is visible as off`() {
        journal().append(
            maneuverGaode = 2, distanceMeters = 90, f28 = 2,
            amapIcon = null, roundaboutNum = null, suppressArrow = true,
        )
        assertTrue(journal().lines().single().endsWith("f28=2 amap=off suppress=true"))
    }

    @Test fun `ring drops the oldest entries past the cap`() {
        val j = journal()
        repeat(HudManeuverJournal.MAX_ENTRIES + 3) { i ->
            clock += 1000
            j.append(
                maneuverGaode = i, distanceMeters = 100, f28 = 1,
                amapIcon = 9, roundaboutNum = null, suppressArrow = false,
            )
        }
        val lines = j.lines()
        assertEquals(HudManeuverJournal.MAX_ENTRIES, lines.size)
        assertTrue(lines.first().contains("gaode=3 "))
        assertTrue(lines.last().contains("gaode=${HudManeuverJournal.MAX_ENTRIES + 2} "))
    }

    @Test fun `push loop records one line per maneuver change, not per frame`() {
        installAmapService()
        val loop = HudPushLoop(FakeSink(), nowMsProvider = { clock },
            amap = HudAmapBroadcaster(context), maneuvers = journal())

        guidance(gaode = 2)
        loop.tick(wasActive = false)
        loop.tick(wasActive = true)
        loop.tick(wasActive = true)      // same maneuver: still one line
        assertEquals(1, journal().lines().size)

        guidance(gaode = 27, distance = 300)
        loop.tick(wasActive = true)

        val lines = journal().lines()
        assertEquals(2, lines.size)
        // f28 = the SOME/IP arrow, amap = the broadcast icon: on the third exit of a roundabout
        // the arrow field is suppressed (0) while the Amap channel carries exit 3 of
        // NUMBERED_ROUNDABOUT_EXIT (12) — exactly the comparison #94 needs.
        assertTrue(lines[0].endsWith("gaode=2 dist=250m f28=2 amap=3 suppress=false"))
        assertTrue(lines[1].endsWith("gaode=27 dist=300m f28=0 amap=12 rab=3 suppress=false"))
    }

    @Test fun `camera takeover is recorded as a suppression change of the same maneuver`() {
        val loop = HudPushLoop(FakeSink(), nowMsProvider = { clock }, maneuvers = journal())

        guidance(gaode = 2)
        loop.tick(wasActive = false)

        NavGuidanceHub.updateFromNotification(
            NavGuidanceHub.RichUpdate(
                maneuverGaode = 2, distanceMeters = 900, road = "A",
                cameraAlert = "camera", cameraDistanceMeters = 400,
            ),
            nowMs = clock,
        )
        loop.tick(wasActive = true)

        val lines = journal().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[1].endsWith("f28=0 amap=off suppress=true"))
    }

    @Test fun `a new guidance session re-records its first maneuver`() {
        val loop = HudPushLoop(FakeSink(), nowMsProvider = { clock }, maneuvers = journal())

        guidance(gaode = 2)
        loop.tick(wasActive = false)
        NavGuidanceHub.reset()
        loop.tick(wasActive = true)      // clear frame, no journal line
        assertEquals(1, journal().lines().size)

        guidance(gaode = 2)
        loop.tick(wasActive = false)
        assertEquals(2, journal().lines().size)
    }
}
