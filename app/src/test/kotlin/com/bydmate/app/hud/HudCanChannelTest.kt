package com.bydmate.app.hud

import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
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
}
