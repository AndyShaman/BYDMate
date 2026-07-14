package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterMode
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsLaunchTest {

    private val gate = mockk<VoiceGate>()
    private val battery = mockk<BatteryStateRepository>()
    private val range = mockk<RangeCalculator>()
    private val tripDao = mockk<TripDao>()
    private val chargeDao = mockk<ChargeDao>()
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
    private val ruleDao = mockk<RuleDao>()
    private val engine = mockk<AutomationEngine>()
    private val places = mockk<PlaceRepository>()
    private val weather = mockk<WeatherClient>()
    private val exa = mockk<ExaSearchClient>()
    private val openRouterClient = mockk<OpenRouterClient>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val contactLookup = mockk<ContactLookup>()
    private val context = mockk<Context>(relaxed = true)
    private val clusterVoiceControl = mockk<ClusterVoiceControl>(relaxUnitFun = true)
    private val chargerSearchClient = mockk<ChargerSearchClient>(relaxed = true)

    private fun tools() = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context, clusterVoiceControl,
        chargerSearchClient,
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).apply {
        clusterPollIntervalMs = 0L; clusterPollAttempts = 5
        naviForegroundCheck = { true }; naviVerifyAttempts = 1; naviVerifyIntervalMs = 1L
    }

    // (a) destination matches a saved place (case-insensitively) -> route by lat/lon.
    @Test fun navigate_to_saved_place_routes_by_coords() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Дом", lat = 55.75, lon = 37.61, radiusM = 100),
        )
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"дом"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Дом", out.getString("place"))
        assertEquals("navigate", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals(55.75, payload.getDouble("lat"), 0.0001)
        assertEquals(37.61, payload.getDouble("lon"), 0.0001)
    }

    // (b) free-text address with no matching place -> search payload.
    @Test fun navigate_to_free_text_opens_search() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        coEvery { places.getAllSnapshot() } returns emptyList()
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"улица Ленина 5"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("search", out.getString("mode"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("улица Ленина 5", payload.getString("query"))
    }

    // (c) placeRepository throwing must not surface as an exception; "Дом" resolves as a
    // home/work shortcut and falls through to Navigator's own saved Home -> mode=route.
    @Test fun navigate_to_survives_place_lookup_failure() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        coEvery { places.getAllSnapshot() } throws RuntimeException("db down")
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"Дом"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("route", out.getString("mode"))
        assertEquals("Дом", out.getString("target"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("home", payload.getString("shortcut"))
    }

    // (d) empty destination -> error, dispatcher never called.
    @Test fun navigate_to_empty_destination_reports_error() = runTest {
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"  "}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // (e) go_home dispatches the go_home kind.
    @Test fun go_home_dispatches_go_home_kind() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(AgentToolCall("1", "go_home", "{}")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("go_home", captured.captured.kind)
    }

    // (f) dispatcher failure -> Russian error JSON.
    @Test fun navigate_to_dispatch_failure_reports_error() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "не получилось открыть Навигатор")
        coEvery { places.getAllSnapshot() } returns emptyList()
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "navigate_to", """{"destination":"Сочи"}""")))
        assertFalse(out.has("ok"))
        assertEquals("не получилось открыть Навигатор", out.getString("error"))
    }

    // (g) play_music without query -> Моя волна via mybeat mode.
    @Test fun play_music_without_query_plays_mybeat() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(AgentToolCall("1", "play_music", "{}")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Моя волна", out.getString("playing"))
        assertEquals("yandex_music", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("mybeat", payload.getString("mode"))
    }

    // (h) play_music with query and no mode -> plays it directly (mode=play by default).
    @Test fun play_music_with_query_plays_by_default() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "play_music", """{"query":"Кино"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Кино", out.getString("playing"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("play", payload.getString("mode"))
        assertEquals("Кино", payload.getString("query"))
    }

    // (h2) play_music with query and mode=search -> only opens the search screen.
    @Test fun play_music_with_query_and_search_mode_only_searches() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "play_music", """{"query":"Кино","mode":"search"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Кино", out.getString("playing"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("search", payload.getString("mode"))
        assertEquals("Кино", payload.getString("query"))
    }

    // (i) dispatcher failure -> Russian error JSON.
    @Test fun play_music_dispatch_failure_reports_error() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "не получилось включить музыку")
        val out = JSONObject(tools().execute(AgentToolCall("1", "play_music", "{}")))
        assertFalse(out.has("ok"))
        assertEquals("не получилось включить музыку", out.getString("error"))
    }

    // --- youtube ---

    // (a) play mode (default) -> dispatches kind=youtube mode=play with query.
    @Test fun youtube_play_dispatches_youtube_kind() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "youtube", """{"query":"обзор leopard 3"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("обзор leopard 3", out.getString("playing"))
        assertEquals("youtube", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("play", payload.getString("mode"))
        assertEquals("обзор leopard 3", payload.getString("query"))
    }

    // (b) mode=search is forwarded as-is.
    @Test fun youtube_search_mode_is_forwarded() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "youtube", """{"query":"тест драйв","mode":"search"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("тест драйв", out.getString("searching"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("search", payload.getString("mode"))
        assertEquals("тест драйв", payload.getString("query"))
    }

    // (c) empty query -> BAD_ARGS_ERROR, dispatcher never called.
    @Test fun youtube_empty_query_reports_bad_args() = runTest {
        val out = JSONObject(tools().execute(AgentToolCall("1", "youtube", "{}")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // --- launch_app ---

    // Four-app fixture: label -> package. Includes «Навигатор» and «Настройки».
    private val launcherFixture = listOf(
        "Навигатор" to "ru.yandex.yandexnavi",
        "Настройки" to "com.android.settings",
        "Яндекс Музыка" to "ru.yandex.music",
        "Телефон" to "com.android.dialer",
    )

    // (a) exact case-insensitive match -> app_launch with the app's packageName.
    @Test fun launch_app_exact_match_dispatches_package() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val t = tools().apply { launcherAppsProvider = { launcherFixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"навигатор"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("app_launch", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("ru.yandex.yandexnavi", payload.getString("packageName"))
    }

    // (b) partial unambiguous match -> launches Settings.
    @Test fun launch_app_partial_unambiguous_launches() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val t = tools().apply { launcherAppsProvider = { launcherFixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"настрой"}""")))
        assertTrue(out.getBoolean("ok"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("com.android.settings", payload.getString("packageName"))
    }

    // (c) ambiguous partial match -> error listing candidates, dispatcher never called.
    @Test fun launch_app_ambiguous_reports_candidates() = runTest {
        val fixture = listOf(
            "Карты" to "ru.yandex.yandexmaps",
            "Карточки" to "com.example.cards",
        )
        val t = tools().apply { launcherAppsProvider = { fixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"карт"}""")))
        assertFalse(out.has("ok"))
        assertTrue(out.getString("error").contains("Карты"))
        assertTrue(out.getString("error").contains("Карточки"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // (d) no match -> not-found error.
    @Test fun launch_app_not_found_reports_error() = runTest {
        val t = tools().apply { launcherAppsProvider = { launcherFixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"калькулятор"}""")))
        assertFalse(out.has("ok"))
        assertTrue(out.getString("error").contains("приложение не найдено"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // (e) throwing provider -> error JSON, not a raw exception.
    @Test fun launch_app_provider_throws_reports_error() = runTest {
        val t = tools().apply { launcherAppsProvider = { throw RuntimeException("pm down") } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"навигатор"}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // (a2) RU alias "камера" resolves to the Chinese label via com.byd.avc.
    @Test fun launch_app_alias_camera_resolves_chinese_label() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val fixture = listOf("全景影像" to "com.byd.avc")
        val t = tools().apply { launcherAppsProvider = { fixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"камера"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("全景影像", out.getString("app"))
        assertEquals("app_launch", captured.captured.kind)
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("com.byd.avc", payload.getString("packageName"))
    }

    // (b2) alias lookup is case-insensitive.
    @Test fun launch_app_alias_camera_case_insensitive() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val fixture = listOf("全景影像" to "com.byd.avc")
        val t = tools().apply { launcherAppsProvider = { fixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"Камера"}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("全景影像", out.getString("app"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("com.byd.avc", payload.getString("packageName"))
    }

    // (c2) alias "ютуб" has two candidate packages in priority order; only the second is installed.
    @Test fun launch_app_alias_youtube_falls_back_to_second_candidate() = runTest {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        val fixture = listOf("YouTube" to "com.google.android.youtube")
        val t = tools().apply { launcherAppsProvider = { fixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"ютуб"}""")))
        assertTrue(out.getBoolean("ok"))
        val payload = JSONObject(captured.captured.payload!!)
        assertEquals("com.google.android.youtube", payload.getString("packageName"))
    }

    // (d2) alias "видеорегистратор" has no candidate installed and no label match -> not-found error.
    @Test fun launch_app_alias_dashcam_not_installed_reports_not_found() = runTest {
        val t = tools().apply { launcherAppsProvider = { launcherFixture } }
        val out = JSONObject(t.execute(AgentToolCall("1", "launch_app", """{"name":"видеорегистратор"}""")))
        assertFalse(out.has("ok"))
        assertTrue(out.getString("error").contains("приложение не найдено"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    // --- set_cluster_projection ---

    // (a) on=true, before=OFF, after=FULLSCREEN -> apply(true), ok:true with app label.
    @Test fun cluster_projection_on_toggles_and_confirms() = runTest {
        every { clusterVoiceControl.projectionMode() } returnsMany
            listOf(ClusterMode.OFF, ClusterMode.FULLSCREEN)
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":true}""")))
        assertTrue(out.getBoolean("ok"))
        assertFalse(out.has("note"))
        assertEquals("Навигатор", out.getString("app"))
        verify { clusterVoiceControl.apply(true) }
    }

    // (b) on=true, before=FULLSCREEN -> apply not called, ok:true "already" note with label.
    @Test fun cluster_projection_on_when_already_on_is_noop() = runTest {
        every { clusterVoiceControl.projectionMode() } returns ClusterMode.FULLSCREEN
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":true}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Навигатор уже на приборке", out.getString("note"))
        verify(exactly = 0) { clusterVoiceControl.apply(any()) }
    }

    // (c) on=true, before=OFF, still OFF after delay -> retry hint. Wave P: the compositor is
    // powered automatically around projection, so the old manual "Full + Navi" precondition
    // hint is gone; the honest advice is to retry in a few seconds.
    @Test fun cluster_projection_on_stuck_reports_retry_hint() = runTest {
        every { clusterVoiceControl.projectionMode() } returns ClusterMode.OFF
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":true}""")))
        assertFalse(out.has("ok"))
        val error = out.getString("error")
        assertTrue(error.contains("Попробуй повторить"))
        assertFalse(error.contains("Navi"))
        verify { clusterVoiceControl.apply(true) }
    }

    // (c2) on=true, still OFF after delay, lastFailure()="daemon" -> honest daemon-down note,
    // no Full/Navi precondition hint (that hint is only for the manual-precondition case).
    @Test fun cluster_projection_on_stuck_daemon_down_reports_honest_note() = runTest {
        every { clusterVoiceControl.projectionMode() } returns ClusterMode.OFF
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        every { clusterVoiceControl.lastFailure() } returns "daemon"
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":true}""")))
        assertFalse(out.getBoolean("ok"))
        val note = out.getString("note")
        assertTrue(note.contains("попробуй ещё раз"))
        assertFalse(note.contains("Navi"))
        verify { clusterVoiceControl.apply(true) }
    }

    // (g) Regression: the mode lands on a later poll — the tool must keep polling and
    // report success instead of the old single-check false negative.
    @Test fun cluster_projection_on_succeeds_when_mode_lands_on_later_poll() = runTest {
        every { clusterVoiceControl.projectionMode() } returnsMany
            listOf(ClusterMode.OFF, ClusterMode.OFF, ClusterMode.OFF, ClusterMode.FULLSCREEN)
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":true}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("Навигатор", out.getString("app"))
        verify { clusterVoiceControl.apply(true) }
    }

    // (d) on=false, before=FULLSCREEN, after=OFF -> apply(false), ok:true.
    @Test fun cluster_projection_off_toggles_and_confirms() = runTest {
        every { clusterVoiceControl.projectionMode() } returnsMany
            listOf(ClusterMode.FULLSCREEN, ClusterMode.OFF)
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":false}""")))
        assertTrue(out.getBoolean("ok"))
        verify { clusterVoiceControl.apply(false) }
    }

    // (e) on=false, before=OFF -> apply not called, note "проекции уже нет".
    @Test fun cluster_projection_off_when_already_off_is_noop() = runTest {
        every { clusterVoiceControl.projectionMode() } returns ClusterMode.OFF
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":false}""")))
        assertTrue(out.getBoolean("ok"))
        assertEquals("проекции уже нет на приборке", out.getString("note"))
        verify(exactly = 0) { clusterVoiceControl.apply(any()) }
    }

    // (f) projectionMode() throws -> treated as OFF, not propagated; on=true still actuates.
    @Test fun cluster_projection_unreadable_state_treated_as_off_and_still_actuates() = runTest {
        every { clusterVoiceControl.projectionMode() } throws RuntimeException("boom")
        every { clusterVoiceControl.projectedAppLabel() } returns "Навигатор"
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", """{"on":true}""")))
        assertTrue(out.has("error"))
        verify { clusterVoiceControl.apply(true) }
    }

    // missing "on" argument -> error, apply not called.
    @Test fun cluster_projection_missing_on_reports_error() = runTest {
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "set_cluster_projection", "{}")))
        assertTrue(out.has("error"))
        verify(exactly = 0) { clusterVoiceControl.apply(any()) }
    }
}
