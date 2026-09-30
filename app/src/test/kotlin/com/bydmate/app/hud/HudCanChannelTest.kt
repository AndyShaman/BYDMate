package com.bydmate.app.hud

import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HudCanChannelTest {

    @Test fun `clear writes a single space as the road name, the car rejects an empty buffer`() = runTest {
        val helper: HelperClient = mockk(relaxed = true)
        val roadBuffers = mutableListOf<ByteArray>()
        coEvery { helper.writeBufferStatus(HudCanChannel.DEV, HudCanChannel.FID_NEXT_PATHNAME, any()) } coAnswers {
            roadBuffers += arg<ByteArray>(2)
            0
        }

        HudCanChannel(helper).clear()

        assertArrayEquals(" ".toByteArray(Charsets.UTF_16LE), roadBuffers.single())
    }

    @Test fun `clear writes the SDK's invalid distance 0 and a blank icon into both icon fids`() = runTest {
        val helper: HelperClient = mockk(relaxed = true)
        val writes = mutableListOf<Pair<Int, Int>>()
        coEvery { helper.writeStatus(HudCanChannel.DEV, any(), any(), any()) } coAnswers {
            writes += arg<Int>(1) to arg<Int>(2)
            1
        }

        HudCanChannel(helper).clear()

        assertEquals(
            listOf(
                HudCanChannel.FID_TURN_KIND to 0,
                HudCanChannel.FID_GUIDE_INFO_ROAD_AHEAD to 0,
                HudCanChannel.FID_TURN_DISTANCE_M to 0,
            ),
            writes,
        )
    }

    @Test fun `turn kinds are the firmware SDK's values`() {
        // BYDAutoInstrumentDevice: TURN_KIND_BLANK = 0, TURN_KIND_LEFT = 7, DISTANCE_INVALID = 0.
        assertEquals(0, HudCanChannel.TURN_NONE)
        assertEquals(7, HudCanChannel.TURN_LEFT)
        assertEquals(0, HudCanChannel.DISTANCE_NONE)
    }
}
