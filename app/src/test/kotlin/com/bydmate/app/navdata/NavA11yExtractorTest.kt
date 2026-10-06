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

    @Test fun `maps package without guidance widgets is no-guidance`() {
        val node = AccessibilityNodeInfo.obtain()
        node.packageName = "ru.yandex.yandexmaps"
        assertEquals(NavA11yExtractor.ReadResult.NoGuidance, NavA11yExtractor.read(node))
    }

    @Test fun `a vocabulary value is kept, anything else is a star with its length`() {
        assertEquals("\"налево\" len=6", NavA11yExtractor.maskValue("налево"))
        assertEquals("\"Поверните направо\" len=17", NavA11yExtractor.maskValue("Поверните направо"))
        assertEquals("\">>>\" len=3", NavA11yExtractor.maskValue(">>>"))
        assertEquals("* len=22", NavA11yExtractor.maskValue("налево на Ленина улицу"))
        assertEquals("* len=3", NavA11yExtractor.maskValue("300"))
        assertEquals("* len=0", NavA11yExtractor.maskValue(""))
    }

    @Test fun `node facts list only present fields, masked, with the short id and class`() {
        val facts = NavA11yExtractor.NodeFacts(
            viewId = "ru.yandex.yandexnavi:id/image_maneuverballoon_maneuver",
            className = "android.widget.ImageView",
            fields = listOf("text" to null, "desc" to "Тверская улица", "state" to "налево", "hint" to null),
            drawingOrder = 3, selected = false, checked = true, extrasKeys = listOf("b.key", "a.key"),
        )
        assertEquals(
            "{id=image_maneuverballoon_maneuver cls=ImageView desc=* len=14 state=\"налево\" len=6 " +
                "order=3 sel=false chk=true extras=[a.key,b.key]}",
            NavA11yExtractor.formatNode(facts),
        )
    }

    @Test fun `node facts with nothing readable print question marks`() {
        val facts = NavA11yExtractor.NodeFacts(null, null, emptyList(), null, null, null, emptyList())
        assertEquals("{id=? cls=? order=? sel=? chk=? extras=[]}", NavA11yExtractor.formatNode(facts))
    }
}
