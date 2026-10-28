package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #325: Navigator 24.3.1 has the maneuver node without a description. The tree is the id walk of
 *  navdata/issue325-logs.txt (the "nav tree" line), the node as the "maneuver unknown" line gives it. */
@RunWith(RobolectricTestRunner::class)
class NavA11yExtractorIssue325Test {

    private val treeIds: Set<String> = requireNotNull(javaClass.classLoader?.getResource("navdata/issue325-logs.txt"))
        .readText().lines().single { "nav tree [gaode=0]:" in it }
        .substringAfter("nav tree [gaode=0]: ").split(' ').map { it.substringBefore(':') }.toSet()

    /** The Han tree: only the ids the log shows exist; texts are made up, the maneuver has no description. */
    private fun hanRoot(withManeuverNode: Boolean): AccessibilityNodeInfo {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.packageName } returns PKG
        every { root.findAccessibilityNodeInfosByViewId(any()) } returns emptyList()
        val texts = mapOf("text_maneuverballoon_distance" to "10", "text_maneuverballoon_metrics" to "м",
            "textview_eta_distance" to "8 км")
        for ((id, text) in texts) {
            assertTrue("$id is in the logged tree", id in treeIds)
            every { root.findAccessibilityNodeInfosByViewId("$PKG:id/$id") } returns listOf(node { every { it.text } returns text })
        }
        assertTrue(MANEUVER in treeIds)
        if (withManeuverNode) {
            every { root.findAccessibilityNodeInfosByViewId("$PKG:id/$MANEUVER") } returns
                listOf(node { every { it.className } returns "android.widget.ImageView" })
        }
        return root
    }

    private fun node(setup: (AccessibilityNodeInfo) -> Unit): AccessibilityNodeInfo {
        val n = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { n.text } returns null
        every { n.contentDescription } returns null
        setup(n)
        return n
    }

    private fun read(root: AccessibilityNodeInfo): NavGuidance =
        (NavA11yExtractor.read(root) as NavA11yExtractor.ReadResult.Guidance).data

    @Test fun `the maneuver node without a description is flagged undescribed (#325)`() {
        val data = read(hanRoot(withManeuverNode = true))
        assertEquals(0, data.maneuverGaode)
        assertEquals(10, data.distanceMeters)
        assertTrue("node found, desc null", data.maneuverUndescribed)
    }

    @Test fun `an absent maneuver node is not undescribed (294)`() {
        val data = read(hanRoot(withManeuverNode = false))
        assertEquals(10, data.distanceMeters)
        assertFalse(data.maneuverUndescribed)
    }

    private companion object {
        const val PKG = "ru.yandex.yandexnavi"
        const val MANEUVER = "image_maneuverballoon_maneuver"
    }
}
