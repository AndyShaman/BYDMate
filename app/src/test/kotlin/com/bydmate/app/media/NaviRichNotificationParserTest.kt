package com.bydmate.app.media

import android.content.Context
import android.graphics.drawable.Icon
import android.view.View
import android.widget.RemoteViews
import com.bydmate.app.media.NaviRichNotificationParser.Op
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 32])
class NaviRichNotificationParserTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun resolver(vararg pairs: Pair<Int, String>): (Int) -> String? {
        val map = pairs.toMap()
        return { id -> map[id] }
    }

    private fun rv(): RemoteViews = RemoteViews(context.packageName, android.R.layout.activity_list_item)

    @Test
    fun `extracts setText with lowercased view name`() {
        val views = rv()
        views.setTextViewText(android.R.id.text1, "230 м")
        val actions = NaviRichNotificationParser.extractRichActions(
            views, resolver(android.R.id.text1 to "TitleView"))
        assertTrue(actions.any { it.viewName == "titleview" && it.op == Op.TEXT && it.value == "230 м" })
    }

    @Test
    fun `extracts setImageViewResource with lowercased drawable name`() {
        val views = rv()
        views.setImageViewResource(android.R.id.icon, android.R.drawable.ic_delete)
        val actions = NaviRichNotificationParser.extractRichActions(
            views,
            resolver(
                android.R.id.icon to "PrimaryIcon",
                android.R.drawable.ic_delete to "Road_Alerts_Camera_32",
            ))
        assertTrue(actions.any { it.viewName == "primaryicon" && it.op == Op.IMAGE_RES && it.value == "road_alerts_camera_32" })
    }

    @Test
    fun `extracts setImageViewIcon resource icon`() {
        val views = rv()
        views.setImageViewIcon(
            android.R.id.icon, Icon.createWithResource(context, android.R.drawable.ic_lock_lock))
        val actions = NaviRichNotificationParser.extractRichActions(
            views,
            resolver(
                android.R.id.icon to "primaryIconTinted",
                android.R.drawable.ic_lock_lock to "notification_right_sdl",
            ))
        assertTrue(actions.any { it.viewName == "primaryicontinted" && it.op == Op.IMAGE_RES && it.value == "notification_right_sdl" })
    }

    @Test
    fun `extracts setViewVisibility`() {
        val views = rv()
        views.setViewVisibility(android.R.id.text1, View.GONE)
        val actions = NaviRichNotificationParser.extractRichActions(
            views, resolver(android.R.id.text1 to "titleview"))
        assertTrue(actions.any { it.viewName == "titleview" && it.op == Op.VISIBILITY && it.value == View.GONE.toString() })
    }

    @Test
    fun `unresolvable ids give empty names without crash`() {
        val views = rv()
        views.setTextViewText(android.R.id.text1, "x")
        val actions = NaviRichNotificationParser.extractRichActions(views) { null }
        assertTrue(actions.any { it.viewName == "" && it.op == Op.TEXT && it.value == "x" })
    }

    // -- buildFromActions --

    private fun textAction(view: String, value: String) =
        NaviRichNotificationParser.RichAction(view, Op.TEXT, value)
    private fun imageAction(view: String, value: String) =
        NaviRichNotificationParser.RichAction(view, Op.IMAGE_RES, value)

    @Test
    fun `actions camera alert with distance from titleview`() {
        val info = NaviRichNotificationParser.buildFromActions(listOf(
            imageAction("primaryicon", "road_alerts_camera_32"),
            textAction("titleview", "230 м"),
        ))
        assertEquals("camera", info.cameraAlert)
        assertEquals(230, info.cameraDistanceM)
        assertEquals(230, info.distToManeuverM)
    }

    @Test
    fun `actions maneuver from primaryicontinted`() {
        val info = NaviRichNotificationParser.buildFromActions(listOf(
            imageAction("primaryicontinted", "notification_right_sdl"),
            textAction("titleview", "500 м"),
        ))
        assertEquals(2, info.maneuverGaode)
        assertEquals(500, info.distToManeuverM)
        assertEquals("", info.cameraAlert)
    }

    @Test
    fun `actions road from descriptionview filters maneuver and time`() {
        assertEquals("улица Ленина", NaviRichNotificationParser.buildFromActions(listOf(
            textAction("descriptionview", "улица Ленина"))).road)
        assertEquals("", NaviRichNotificationParser.buildFromActions(listOf(
            textAction("descriptionview", "Поверните направо"))).road)
        assertEquals("", NaviRichNotificationParser.buildFromActions(listOf(
            textAction("descriptionview", "12:45"))).road)
        assertEquals("", NaviRichNotificationParser.buildFromActions(listOf(
            textAction("descriptionview", "setText"))).road)
    }

    @Test
    fun `actions road falls back to titleview after middle dot`() {
        val info = NaviRichNotificationParser.buildFromActions(listOf(
            textAction("titleview", "500 м · Тверская"),
        ))
        assertEquals("Тверская", info.road)
        assertEquals(500, info.distToManeuverM)
    }

    // -- mergePreferActions --

    @Test
    fun `merge takes camera from actions and icon from render`() {
        val actions = NaviRichNotificationParser.RichNaviInfo(cameraAlert = "camera")
        val render = NaviRichNotificationParser.RichNaviInfo(
            cameraDistanceM = 150, cameraIconPng = byteArrayOf(1, 2))
        val merged = NaviRichNotificationParser.mergePreferActions(actions, render)!!
        assertEquals("camera", merged.cameraAlert)
        assertEquals(150, merged.cameraDistanceM)
        assertArrayEquals(byteArrayOf(1, 2), merged.cameraIconPng)
    }

    @Test
    fun `merge without actions camera drops render camera candidates`() {
        val actions = NaviRichNotificationParser.RichNaviInfo(distToManeuverM = 300)
        val render = NaviRichNotificationParser.RichNaviInfo(
            cameraDistanceM = 150, cameraIconPng = byteArrayOf(1))
        val merged = NaviRichNotificationParser.mergePreferActions(actions, render)!!
        assertEquals("", merged.cameraAlert)
        assertEquals(0, merged.cameraDistanceM)
        assertNull(merged.cameraIconPng)
    }

    @Test
    fun `merge prefers actions maneuver and road, render instruction and totals`() {
        val actions = NaviRichNotificationParser.RichNaviInfo(
            road = "Тверская", distToManeuverM = 300, maneuverGaode = 2)
        val render = NaviRichNotificationParser.RichNaviInfo(
            instruction = "Поверните направо", road = "другая", distToManeuverM = 100,
            totalDistM = 5000, remainingTimeSec = 600, maneuverPng = byteArrayOf(9), maneuverGaode = 1)
        val merged = NaviRichNotificationParser.mergePreferActions(actions, render)!!
        assertEquals("Поверните направо", merged.instruction)
        assertEquals("Тверская", merged.road)
        assertEquals(300, merged.distToManeuverM)
        assertEquals(5000, merged.totalDistM)
        assertEquals(600, merged.remainingTimeSec)
        assertArrayEquals(byteArrayOf(9), merged.maneuverPng)
        assertEquals(2, merged.maneuverGaode)
    }

    @Test
    fun `merge with one side null returns the other`() {
        val one = NaviRichNotificationParser.RichNaviInfo(road = "x")
        assertEquals(one, NaviRichNotificationParser.mergePreferActions(one, null))
        assertEquals(one, NaviRichNotificationParser.mergePreferActions(null, one))
        assertNull(NaviRichNotificationParser.mergePreferActions(null, null))
    }

    // -- hasNaviSignal --

    @Test
    fun `stub without signals has no navi signal`() {
        assertFalse(NaviRichNotificationParser.hasNaviSignal(NaviRichNotificationParser.RichNaviInfo()))
        assertFalse(NaviRichNotificationParser.hasNaviSignal(
            NaviRichNotificationParser.RichNaviInfo(instruction = "x", arrivalTime = "12:00", remainingTimeSec = 60)))
    }

    @Test
    fun `each guidance signal counts`() {
        assertTrue(NaviRichNotificationParser.hasNaviSignal(NaviRichNotificationParser.RichNaviInfo(maneuverGaode = 2)))
        assertTrue(NaviRichNotificationParser.hasNaviSignal(NaviRichNotificationParser.RichNaviInfo(distToManeuverM = 1)))
        assertTrue(NaviRichNotificationParser.hasNaviSignal(NaviRichNotificationParser.RichNaviInfo(totalDistM = 1)))
        assertTrue(NaviRichNotificationParser.hasNaviSignal(NaviRichNotificationParser.RichNaviInfo(road = "x")))
        assertTrue(NaviRichNotificationParser.hasNaviSignal(NaviRichNotificationParser.RichNaviInfo(cameraAlert = "camera")))
    }

    @Test
    fun `distance and duration helpers`() {
        assertEquals(230, NaviRichNotificationParser.parseDistanceOrNull("230 м"))
        assertEquals(1500, NaviRichNotificationParser.parseDistanceOrNull("1,5 км"))
        assertNull(NaviRichNotificationParser.parseDistanceOrNull("улица Ленина"))
        assertEquals(6000, NaviRichNotificationParser.parseDuration("1 ч 40 мин"))
        assertEquals(1800, NaviRichNotificationParser.parseDuration("30 мин"))
        assertTrue(NaviRichNotificationParser.isDurationText("1 ч 40 мин"))
        assertTrue(NaviRichNotificationParser.isDurationText("1:30"))
        assertFalse(NaviRichNotificationParser.isDurationText("улица Ленина"))
    }

    // -- classify (render path) --

    private fun text(name: String, value: String) = NaviRichNotificationParser.NamedText(name, value)
    private fun image(name: String, w: Int = 64, h: Int = 64, png: ByteArray? = byteArrayOf(1)) =
        NaviRichNotificationParser.NamedImage(name, w, h) { png }

    @Test
    fun `classify reads named fields`() {
        val info = NaviRichNotificationParser.classify(
            listOf(
                text("titleview", "500 м · Тверская"),
                text("descriptionview", "Поверните направо"),
                text("remainingdistanceview", "5 км"),
                text("timeofarrivalview", "Прибытие в 12:45"),
                text("remainingtimeview", "27 мин"),
            ),
            emptyList(), isMaps = false)
        assertEquals("Поверните направо", info.instruction)
        assertEquals("Тверская", info.road)
        assertEquals(500, info.distToManeuverM)
        assertEquals(5000, info.totalDistM)
        assertEquals("12:45", info.arrivalTime)
        assertEquals(27 * 60, info.remainingTimeSec)
    }

    @Test
    fun `classify maps mode treats description as instruction`() {
        val info = NaviRichNotificationParser.classify(
            listOf(text("descriptionview", "Через 300 м поверните направо")), emptyList(), isMaps = true)
        assertEquals("Через 300 м поверните направо", info.instruction)
        assertEquals(300, info.distToManeuverM) // heuristic top-up finds the distance
    }

    @Test
    fun `classify heuristic distances arrival remaining and road`() {
        val info = NaviRichNotificationParser.classify(
            listOf(
                text("", "230 м"),
                text("", "12 км"),
                text("", "Прибытие в 08:30"),
                text("", "45 мин"),
                text("", "Ленинский проспект"),
            ),
            emptyList(), isMaps = false)
        assertEquals(230, info.distToManeuverM)
        assertEquals(12000, info.totalDistM)
        assertEquals("08:30", info.arrivalTime)
        assertEquals(45 * 60, info.remainingTimeSec)
        assertEquals("Ленинский проспект", info.road)
    }

    @Test
    fun `classify road cleanup drops action buttons and stub text`() {
        assertEquals("", NaviRichNotificationParser.classify(
            listOf(text("descriptionview", "Завершить маршрут")), emptyList(), isMaps = false).road)
        assertEquals("", NaviRichNotificationParser.classify(
            listOf(text("descriptionview", "Навигатор запущен")), emptyList(), isMaps = false).road)
    }

    @Test
    fun `classify picks maneuver png by name then squarest non-blocklisted`() {
        val byName = NaviRichNotificationParser.classify(
            emptyList(),
            listOf(image("etaprogress", 200, 20, byteArrayOf(1)), image("primaryicontinted", png = byteArrayOf(7))),
            isMaps = false)
        assertArrayEquals(byteArrayOf(7), byName.maneuverPng)

        val squarest = NaviRichNotificationParser.classify(
            emptyList(),
            listOf(
                image("etaprogress", 200, 20, byteArrayOf(1)), // blocklisted
                image("banner", 300, 100, byteArrayOf(2)),
                image("somearrow", 60, 64, byteArrayOf(3)),    // closest to square wins
            ),
            isMaps = false)
        assertArrayEquals(byteArrayOf(3), squarest.maneuverPng)
    }

    @Test
    fun `classify keeps camera icon candidate without alert`() {
        val info = NaviRichNotificationParser.classify(
            emptyList(), listOf(image("primaryicon", png = byteArrayOf(9))), isMaps = false)
        assertEquals("", info.cameraAlert)
        assertArrayEquals(byteArrayOf(9), info.cameraIconPng)
        assertNull(info.maneuverPng) // primaryicon is blocklisted as maneuver png
    }

    // -- parse (full flow, Robolectric) --

    @Test
    fun `parse merges actions and render from a real notification`() {
        val views = rv()
        views.setTextViewText(android.R.id.text1, "230 м · Тверская")
        val n = android.app.Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setCustomContentView(views)
            .build()
        val info = NaviRichNotificationParser.parse(
            context, n, "ru.yandex.yandexnavi", resolver(android.R.id.text1 to "titleview"))
        assertEquals(230, info!!.distToManeuverM)
        assertEquals("Тверская", info.road)
    }

    @Test
    fun `parse returns null without remote views`() {
        val n = android.app.Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("x")
            .build()
        assertNull(NaviRichNotificationParser.parse(context, n, "ru.yandex.yandexnavi") { null })
    }
}
