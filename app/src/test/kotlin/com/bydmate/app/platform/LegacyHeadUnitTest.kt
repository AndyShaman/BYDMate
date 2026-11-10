package com.bydmate.app.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyHeadUnitTest {

    @Test fun `android 10 and older are legacy head units, android 11 and newer are not`() {
        val cases = listOf(28 to true, 29 to true, 30 to false, 31 to false, 32 to false)
        for ((sdk, expected) in cases) {
            assertEquals("case: sdk $sdk", expected, LegacyHeadUnit.isAndroid10(sdk))
        }
    }
}
