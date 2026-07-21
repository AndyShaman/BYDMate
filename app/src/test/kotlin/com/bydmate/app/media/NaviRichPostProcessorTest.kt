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
}
