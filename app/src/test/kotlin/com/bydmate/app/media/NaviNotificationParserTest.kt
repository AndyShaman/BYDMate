package com.bydmate.app.media

import android.app.Notification
import android.widget.RemoteViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 32])
class NaviNotificationParserTest {

    // -- pure mapping (no framework) --

    private fun rv(view: String, method: String, value: String) =
        NaviNotificationParser.RvAction(view, method, value)

    @Test fun `maps content actions to maneuver distance street`() {
        val parsed = NaviNotificationParser.fromActions(
            content = listOf(
                rv("primaryIconTinted", "setImageResource", "notification_right_sdl"),
                rv("titleView", "setText", "350 м"),
                rv("descriptionView", "setText", "улица Ленина"),
            ),
            big = listOf(
                rv("123", "setText", "18:40"),
                rv("124", "setText", "42 км"),
            ),
        )
        assertEquals("направо", parsed.maneuver)
        assertEquals("notification_right_sdl", parsed.maneuverResource)
        assertEquals("350 м", parsed.distance)
        assertEquals("улица Ленина", parsed.street)
        assertEquals(listOf("18:40", "42 км"), parsed.bigTexts)
    }

    @Test fun `unknown maneuver resource keeps raw name with null phrase`() {
        val parsed = NaviNotificationParser.fromActions(
            content = listOf(rv("primaryIconTinted", "setImageResource", "notification_new_thing")),
            big = emptyList(),
        )
        assertNull(parsed.maneuver)
        assertEquals("notification_new_thing", parsed.maneuverResource)
    }

    @Test fun `empty actions produce empty parsed`() {
        val parsed = NaviNotificationParser.fromActions(emptyList(), emptyList())
        assertNull(parsed.maneuver)
        assertNull(parsed.distance)
        assertNull(parsed.street)
        assertTrue(parsed.bigTexts.isEmpty())
    }

    // -- reflection plumbing against a real RemoteViews (Robolectric runs framework code) --

    @Test fun `parse extracts setText action from real contentView`() {
        val ctx = RuntimeEnvironment.getApplication()
        val views = RemoteViews(ctx.packageName, android.R.layout.simple_list_item_1)
        views.setTextViewText(android.R.id.text1, "350 м")
        @Suppress("DEPRECATION")
        val notification = Notification().apply { contentView = views }

        val parsed = NaviNotificationParser.parse(notification) { id ->
            if (id == android.R.id.text1) "titleView" else null
        }

        assertEquals("350 м", parsed.distance)
    }

    @Test fun `parse survives notification without custom views`() {
        val parsed = NaviNotificationParser.parse(Notification()) { null }
        assertNull(parsed.distance)
        assertTrue(parsed.bigTexts.isEmpty())
    }
}
