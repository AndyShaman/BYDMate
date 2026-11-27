package com.bydmate.app.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #409: one-time diagnostic probe for non-panoramic cameras whose side views stay black. */
class BlindSpotApaProbeTest {

    @Test fun `shouldProbe triggers only for apa or byd_apa when both lumas are 0 and not yet probed`() {
        assertTrue(BlindSpotApaProbe.shouldProbe("apa", 0, 0, false))
        assertTrue(BlindSpotApaProbe.shouldProbe("byd_apa", 0, 0, false))

        assertFalse(BlindSpotApaProbe.shouldProbe("apa", 0, 0, true))
        assertFalse(BlindSpotApaProbe.shouldProbe("apa", 0, 12, false))
        assertFalse(BlindSpotApaProbe.shouldProbe("apa", 12, 0, false))
        assertFalse(BlindSpotApaProbe.shouldProbe("pano_h", 0, 0, false))
        assertFalse(BlindSpotApaProbe.shouldProbe("pano_l", 0, 0, false))
        assertFalse(BlindSpotApaProbe.shouldProbe(null, 0, 0, false))
        assertFalse(BlindSpotApaProbe.shouldProbe("apa", null, 0, false))
        assertFalse(BlindSpotApaProbe.shouldProbe("apa", 0, null, false))
    }
}
