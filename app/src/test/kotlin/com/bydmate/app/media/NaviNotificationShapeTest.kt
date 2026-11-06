package com.bydmate.app.media

import com.bydmate.app.media.NaviNotificationParser.RvAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #301: an empty Navigator 31.x notification is logged by its shape only, never by its texts. */
class NaviNotificationShapeTest {

    private val content = listOf(
        RvAction("titleView", "setText", "350 м"),
        RvAction("descriptionView", "setText", "улица Ленина"),
        RvAction("primaryIconTinted", "setImageResource", "notification_right_sdl"),
        RvAction("root", "setBackgroundColor", "-16777216"),
    )

    @Test fun texts_become_lengths_and_never_reach_the_line() {
        val line = NaviNotificationShape.line(
            kind = "empty",
            extrasKeys = setOf("android.title", "android.text"),
            title = "Москва",
            text = "улица Ленина",
            subText = null,
            views = mapOf("contentView" to content),
        )
        assertFalse(line, line.contains("Ленина"))
        assertFalse(line, line.contains("Москва"))
        assertFalse(line, line.contains("350"))
        assertTrue(line, line.contains("title=6"))
        assertTrue(line, line.contains("text=12"))
        assertTrue(line, line.contains("sub=-"))
        assertTrue(line, line.contains("titleView.setText(len=5)"))
        assertTrue(line, line.contains("descriptionView.setText(len=12)"))
    }

    @Test fun image_resource_names_and_other_methods_stay_as_names() {
        val line = NaviNotificationShape.line("stub", emptySet(), null, null, null, mapOf("contentView" to content))
        assertTrue(line, line.contains("primaryIconTinted.setImageResource(notification_right_sdl)"))
        assertTrue(line, line.contains("root.setBackgroundColor"))
        assertFalse(line, line.contains("-16777216"))
    }

    @Test fun extras_keys_are_sorted_and_views_keep_their_field_names() {
        val line = NaviNotificationShape.line(
            kind = "empty",
            extrasKeys = setOf("android.text", "android.title", "android.progress"),
            title = null, text = null, subText = null,
            views = mapOf("contentView" to emptyList(), "bigContentView" to content.take(1)),
        )
        assertEquals(
            "nav notif shape [empty]: extras=[android.progress,android.text,android.title] title=- text=- sub=- " +
                "contentView=[] bigContentView=[titleView.setText(len=5)]",
            line,
        )
    }

    @Test fun absent_views_say_so() {
        val line = NaviNotificationShape.line("empty", emptySet(), null, null, null, emptyMap())
        assertTrue(line, line.endsWith("views=none"))
    }
}
