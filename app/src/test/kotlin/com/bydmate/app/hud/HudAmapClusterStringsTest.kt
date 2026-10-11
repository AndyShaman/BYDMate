package com.bydmate.app.hud

import android.content.Intent
import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #301, Song Plus (DiLink 3.0): the 1for2 cluster draws the adapter's three *_AUTO strings as
 *  they are, and the adapter puts "-1" where a string did not come (AmapService.onReceive). The
 *  frame is the log's 10:17:52 one: right turn in 50 m, 930 m and 3 min to go. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HudAmapClusterStringsTest {

    private fun frame(total: Int = 930, eta: Int = 180): Intent = HudAmapClusterLoop.buildGuideIntent(
        NavGuidanceHub.Snapshot(active = true, maneuverGaode = 2, distanceMeters = 50,
            road = "Maple Avenue", etaSeconds = eta, totalDistMeters = total))

    @Test fun `the distance to the maneuver goes out as the string the cluster draws`() {
        assertEquals("50 m", frame().getStringExtra("SEG_REMAIN_DIS_AUTO"))
    }

    @Test fun `the rest of the route goes out as the string the cluster draws`() {
        assertEquals("930 m", frame().getStringExtra("ROUTE_REMAIN_DIS_AUTO"))
    }

    @Test fun `the remaining time carries no Chinese unit`() {
        assertEquals("3 min", frame().getStringExtra("ROUTE_REMAIN_TIME_AUTO"))
    }
}
