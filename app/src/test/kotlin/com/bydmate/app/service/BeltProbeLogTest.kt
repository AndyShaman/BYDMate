package com.bydmate.app.service

import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Audit 3.19 item 11: the `belt probe:` line, on a change, at most once per 5 s. */
class BeltProbeLogTest {

    private var now = 0L
    private val probe = BeltProbeLog { now }

    private fun data(rl: Int?, settingRl: Int? = null, occupancyRl: Int? = null) =
        diParsData().copy(seatbeltRL = rl, rearBeltSettingRL = settingRl, occupancyRL = occupancyRl)

    @Test fun `prints every rear belt reading next to the seats`() {
        val line = probe.line(diParsData().copy(
            seatbeltRL = 1, seatbeltRM = 0, seatbeltRR = 1,
            rearBeltSettingRL = 2, rearBeltSettingRM = 1, rearBeltSettingRR = 2,
            occupancyRL = 2, occupancyRM = 1, occupancyRR = 2,
        ))

        assertEquals(
            "belt probe: instrument RL=1 RM=0 RR=1 setting RL=2 RM=1 RR=2 occupancy RL=2 RM=1 RR=2",
            line,
        )
    }

    @Test fun `logs a change, not a repeat`() {
        probe.line(data(rl = 0))
        now += 10_000

        assertNull(probe.line(data(rl = 0)))
        assertEquals(true, probe.line(data(rl = 1))?.contains("instrument RL=1"))
    }

    @Test fun `a change inside 5 s waits for the gap`() {
        probe.line(data(rl = 0))
        now += 1_000
        assertNull(probe.line(data(rl = 1)))

        now += BeltProbeLog.MIN_GAP_MS
        assertEquals(true, probe.line(data(rl = 1))?.contains("instrument RL=1"))
    }

    @Test fun `a Setting candidate change is logged too`() {
        probe.line(data(rl = 0, settingRl = 1))
        now += 10_000

        assertEquals(true, probe.line(data(rl = 0, settingRl = 2))?.contains("setting RL=2"))
    }
}
