package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Test

class NavRouteStatsTest {

    @Test fun `the summary lists every counter in its fixed order`() {
        val s = NavRouteStats()
        s.reset(10_000)
        s.a11yGuidance = 40; s.a11yNoGuidance = 3; s.kept = 2
        s.notifRich = 5; s.notifIgnored = 7; s.notifEmpty = 1
        s.maneuvers = 6; s.unknown = 2; s.expiries = 1; s.drops = 1
        repeat(3) { s.frame(0) }
        s.frame(-1)
        s.refreshed(4_500)
        s.refreshed(1_000)
        assertEquals(
            "way=2 dur_s=125 a11y_guid=40 a11y_noguid=3 kept=2 blind_ms=0 notif_rich=5 notif_ignored=7 " +
                "notif_empty=1 maneuvers=6 unknown=2 expiries=1 drops=1 frames=4 rc={-1:1,0:3} max_gap_s=4",
            s.describe(way = 2, endMs = 135_000),
        )
    }

    @Test fun `blind spells add up, one still open counts to the end`() {
        val s = NavRouteStats()
        s.reset(0)
        s.blind(1_000)
        s.blind(1_500)              // still the same spell
        s.seen(3_000)
        s.seen(4_000)               // nothing open
        s.blind(10_000)
        assertEquals(2_000 + 5_000, s.fields(1, endMs = 15_000).toMap()["blind_ms"])
    }

    @Test fun `reset starts the next route from zero`() {
        val s = NavRouteStats()
        s.reset(0)
        s.a11yGuidance = 9
        s.frame(0)
        s.blind(100)
        s.refreshed(9_000)
        s.reset(50_000)
        assertEquals(
            "way=1 dur_s=0 a11y_guid=0 a11y_noguid=0 kept=0 blind_ms=0 notif_rich=0 notif_ignored=0 " +
                "notif_empty=0 maneuvers=0 unknown=0 expiries=0 drops=0 frames=0 rc={} max_gap_s=0",
            s.describe(way = 1, endMs = 50_000),
        )
    }
}
