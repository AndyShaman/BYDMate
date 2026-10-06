package com.bydmate.app.media

import com.bydmate.app.navdata.NavGuidanceHub
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NaviRichPostProcessorTest {

    // -- buildRichUpdate --

    @Test
    fun `eta falls back to subtext only when parser gave none`() {
        val fromSub = NaviRichPostProcessor.buildRichUpdate(
            NaviRichNotificationParser.RichNaviInfo(road = "x"), null, null, "1 ч 5 мин")
        assertEquals(3900, fromSub.etaSeconds)
        val fromRv = NaviRichPostProcessor.buildRichUpdate(
            NaviRichNotificationParser.RichNaviInfo(remainingTimeSec = 600), null, null, "1 ч 5 мин")
        assertEquals(600, fromRv.etaSeconds)
    }

    @Test
    fun `maneuver from instruction when parser gave none`() {
        val u = NaviRichPostProcessor.buildRichUpdate(
            NaviRichNotificationParser.RichNaviInfo(instruction = "Поверните направо"), null, null, null)
        assertEquals(2, u.maneuverGaode)
        val known = NaviRichPostProcessor.buildRichUpdate(
            NaviRichNotificationParser.RichNaviInfo(instruction = "Поверните направо", maneuverGaode = 1),
            null, null, null)
        assertEquals(1, known.maneuverGaode)
    }

    @Test
    fun `extras road wins, duration and maneuver texts filtered`() {
        val rich = NaviRichNotificationParser.RichNaviInfo(road = "Тверская")
        assertEquals("Ленинский проспект",
            NaviRichPostProcessor.buildRichUpdate(rich, null, "Ленинский проспект", null).road)
        assertEquals("Тверская",
            NaviRichPostProcessor.buildRichUpdate(rich, null, "27 мин", null).road)
        val maneuverRoad = NaviRichNotificationParser.RichNaviInfo(road = "Поверните налево")
        assertEquals("", NaviRichPostProcessor.buildRichUpdate(maneuverRoad, null, null, null).road)
    }

    @Test
    fun `extras title distance used only when rv gave none`() {
        val fromTitle = NaviRichPostProcessor.buildRichUpdate(
            NaviRichNotificationParser.RichNaviInfo(road = "x"), "230 м", null, null)
        assertEquals(230, fromTitle.distanceMeters)
        val fromRv = NaviRichPostProcessor.buildRichUpdate(
            NaviRichNotificationParser.RichNaviInfo(road = "x", distToManeuverM = 500), "230 м", null, null)
        assertEquals(500, fromRv.distanceMeters)
    }

    @Test
    fun `camera passthrough with applyCamera true`() {
        val u = NaviRichPostProcessor.buildRichUpdate(
            NaviRichNotificationParser.RichNaviInfo(
                cameraAlert = "camera", cameraDistanceM = 400, cameraIconPng = byteArrayOf(1)),
            null, null, null)
        assertTrue(u.applyCamera)
        assertEquals("camera", u.cameraAlert)
        assertEquals(400, u.cameraDistanceMeters)
        assertArrayEquals(byteArrayOf(1), u.cameraIconPng)
    }

    // -- buildExtrasFallback --

    @Test
    fun `idle notifications are skipped`() {
        assertNull(NaviRichPostProcessor.buildExtrasFallback(
            "Навигатор запущен", "", null, "notifications_app_logo", false, false))
        assertNull(NaviRichPostProcessor.buildExtrasFallback(
            "Навигатор работает", "", null, "some_icon", false, false))
    }

    @Test
    fun `issue 198 the Google Play services notice is no guidance`() {
        assertNull(NaviRichPostProcessor.buildExtrasFallback(
            GPS_NOTICE_TITLE, GPS_NOTICE_TEXT, null, "", false, false))
        assertNull(NaviRichPostProcessor.buildExtrasFallback(
            GPS_NOTICE_TITLE, GPS_NOTICE_TEXT, null, "", true, false))
    }

    @Test
    fun `a parsed distance of 0 m with a street is still guidance`() {
        // What buildExtrasFallback returned for this post before the #198 rule.
        assertEquals(
            NavGuidanceHub.RichUpdate(maneuverGaode = 0, distanceMeters = 0, road = "Тверская",
                etaSeconds = 0, totalDistMeters = 0, maneuverPng = null, applyCamera = false),
            NaviRichPostProcessor.buildExtrasFallback("0 м", "Тверская", null, "", false, false),
        )
    }

    @Test
    fun `fallback maneuver from text then icon`() {
        val fromText = NaviRichPostProcessor.buildExtrasFallback(
            "Поверните направо", "Тверская", null, "", false, false)!!
        assertEquals(2, fromText.maneuverGaode)
        val fromIcon = NaviRichPostProcessor.buildExtrasFallback(
            "500 м", "Тверская", null, "notification_left_sdl", false, false)!!
        assertEquals(1, fromIcon.maneuverGaode)
        assertEquals(500, fromIcon.distanceMeters)
        assertEquals("Тверская", fromIcon.road)
    }

    @Test
    fun `maps extras maneuver dropped while hub has known maneuver`() {
        val dropped = NaviRichPostProcessor.buildExtrasFallback(
            "Поверните направо", "300 м", null, "", true, true)!!
        assertEquals(0, dropped.maneuverGaode)
        val kept = NaviRichPostProcessor.buildExtrasFallback(
            "Поверните направо", "300 м", null, "", true, false)!!
        assertEquals(2, kept.maneuverGaode)
    }

    @Test
    fun `fallback drops stub road, keeps camera untouched, eta from subtext`() {
        val u = NaviRichPostProcessor.buildExtrasFallback(
            "Навигатор запущен", "230 м", "45 мин", "some_icon", false, false)!!
        assertEquals("", u.road)
        assertEquals(230, u.distanceMeters)
        assertEquals(45 * 60, u.etaSeconds)
        assertFalse(u.applyCamera)
        assertEquals(0, u.totalDistMeters)
        assertNull(u.maneuverPng)
    }

    private companion object {
        // Issue #198: what Yandex Navigator posts on a car without Google services (photo of the
        // notification shade; the text is cut there, so only its visible start is used).
        const val GPS_NOTICE_TITLE = "Установите сервисы Google Play"
        const val GPS_NOTICE_TEXT = "Для работы приложения \"Яндекс Навигатор\" требуется"
    }

    // -- #294: the raw input behind the maneuver --

    @Test
    fun `rich raw input is the icon resource, or the instruction head when no icon mapped`() {
        val byIcon = NaviRichPostProcessor.buildRichUpdate(
            NaviRichNotificationParser.RichNaviInfo(maneuverGaode = 2, maneuverRes = "ic_right"), null, null, null)
        assertEquals("res:ic_right", byIcon.maneuverRaw)
        val byText = NaviRichPostProcessor.buildRichUpdate(
            NaviRichNotificationParser.RichNaviInfo(instruction = "Поверните направо на улицу Ленина"), null, null, null)
        assertEquals("text:Поверните направо на улицу", byText.maneuverRaw)
    }

    @Test
    fun `extras raw input is the text when a phrase matched, else the small icon`() {
        val byText = NaviRichPostProcessor.buildExtrasFallback(
            "300 м", "Поверните направо", null, "ic_nav", isMaps = false, hubHasKnownManeuver = false)!!
        assertEquals("text:300 м Поверните направо", byText.maneuverRaw)
        val byIcon = NaviRichPostProcessor.buildExtrasFallback(
            "300 м", "Main St", null, "notification_left_sdl", isMaps = false, hubHasKnownManeuver = false)!!
        assertEquals(1, byIcon.maneuverGaode)
        assertEquals("icon:notification_left_sdl", byIcon.maneuverRaw)
    }
}
