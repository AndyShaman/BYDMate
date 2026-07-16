package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NavA11yExtractorTest {

    @Test fun `null root is not navigator`() {
        assertEquals(NavA11yExtractor.ReadResult.NotNavigator, NavA11yExtractor.read(null))
    }

    @Test fun `foreign package is not navigator`() {
        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "com.android.launcher"
        assertEquals(NavA11yExtractor.ReadResult.NotNavigator, NavA11yExtractor.read(node))
    }

    @Test fun `navigator package without guidance widgets is no-guidance`() {
        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "ru.yandex.yandexnavi"
        assertEquals(NavA11yExtractor.ReadResult.NoGuidance, NavA11yExtractor.read(node))
    }
}
