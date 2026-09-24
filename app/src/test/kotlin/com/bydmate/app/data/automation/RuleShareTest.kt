package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuleShareTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("rule-share/$name")) { name }.readText()

    /** The stored rule from `source_rule.json`: a place trigger, a call, an agent query, a url with keys. */
    private fun sourceEntity(): RuleEntity {
        val json = JSONObject(fixture("source_rule.json"))
        return RuleEntity(
            id = 12,
            name = json.getString("name"),
            triggerLogic = json.getString("trigger_logic"),
            triggers = json.getJSONArray("triggers").toString(),
            actions = json.getJSONArray("actions").toString(),
            cooldownSeconds = json.getInt("cooldown_seconds"),
            requirePark = json.getBoolean("require_park"),
            confirmBeforeExecute = json.getBoolean("confirm_before_execute"),
            fireOncePerTrip = json.getBoolean("fire_once_per_trip"),
            playSound = json.getBoolean("play_sound"),
            lastTriggeredAt = json.getLong("last_triggered_at"),
            triggerCount = json.getInt("trigger_count"),
        )
    }

    private fun export(): JSONObject =
        JSONObject(RuleShare.exportJson(SharedRule.fromEntity(sourceEntity()), "3.18.0"))

    // --- Export ---

    @Test fun `export carries the format header`() {
        val root = export()
        assertEquals("bydmate_rule", root.getString("format"))
        assertEquals(1, root.getInt("version"))
        assertEquals("3.18.0", root.getString("app_version"))
    }

    @Test fun `export drops placeId and keeps placeName`() {
        val trigger = export().getJSONObject("rule").getJSONArray("triggers").getJSONObject(0)
        assertFalse(trigger.has("placeId"))
        assertEquals("Дача", trigger.getString("placeName"))
        assertEquals("place_enter", trigger.getString("kind"))
    }

    @Test fun `export strips the contact from a call and marks it`() {
        val call = export().getJSONObject("rule").getJSONArray("actions").getJSONObject(0)
        assertEquals("call", call.getString("kind"))
        assertEquals("", call.getString("displayName"))
        val payload = JSONObject(call.getString("payload"))
        assertFalse(payload.has("phone"))
        assertFalse(payload.has("name"))
        assertTrue(payload.getBoolean("autoDial"))
        assertTrue(payload.getBoolean("contactRequired"))
        assertFalse(export().toString().contains("375291234567"))
        assertFalse(export().toString().contains("Мама"))
    }

    @Test fun `export keeps the agent prompt as is`() {
        val agent = export().getJSONObject("rule").getJSONArray("actions").getJSONObject(1)
        assertEquals("agent_query", agent.getString("kind"))
        assertEquals("Какая погода дома?", JSONObject(agent.getString("payload")).getString("prompt"))
    }

    @Test fun `export strips credentials from a url and keeps the rest`() {
        val url = export().getJSONObject("rule").getJSONArray("actions").getJSONObject(2)
        val payload = JSONObject(url.getString("payload"))
        assertEquals("https://example.com/path?q=home#top", payload.getString("url"))
        assertFalse(payload.getBoolean("minimize"))
    }

    @Test fun `export keeps settings and never writes runtime fields`() {
        val rule = export().getJSONObject("rule")
        assertEquals("Дом: звонок", rule.getString("name"))
        assertEquals("AND", rule.getString("trigger_logic"))
        assertEquals(30, rule.getInt("cooldown_seconds"))
        assertTrue(rule.getBoolean("require_park"))
        assertFalse(rule.getBoolean("confirm_before_execute"))
        assertTrue(rule.getBoolean("fire_once_per_trip"))
        assertTrue(rule.getBoolean("play_sound"))
        assertEquals("车窗关闭", rule.getJSONArray("actions").getJSONObject(3).getString("command"))
        val text = export().toString()
        listOf("\"id\"", "enabled", "last_triggered", "lastTriggered", "trigger_count", "triggerCount", "created").forEach {
            assertFalse(it, text.contains(it))
        }
    }

    @Test fun `url without credentials is unchanged`() {
        assertEquals("yandexmusic://radio/user/onyourwave?play=true",
            RuleShare.stripUrlCredentials("yandexmusic://radio/user/onyourwave?play=true"))
        assertEquals("https://a.b/c", RuleShare.stripUrlCredentials("https://a.b/c?token=1"))
    }

    // --- File name ---

    @Test fun `slug transliterates Cyrillic`() {
        assertEquals("bagazhnik", RuleShareFiles.slug("Багажник"))
        assertEquals("navi", RuleShareFiles.slug("Navi"))
        assertEquals("zakryt_okna_na_trasse", RuleShareFiles.slug("Закрыть окна на трассе!"))
        assertEquals("shchet_ezh", RuleShareFiles.slug("Щёт ёж"))
        assertEquals("dom_zvonok", RuleShareFiles.slug("Дом: звонок"))
    }

    @Test fun `slug drops accents, caps length and never comes out empty`() {
        assertEquals("zolta_lodz", RuleShareFiles.slug("Żółta łódź"))
        assertEquals("rule", RuleShareFiles.slug("高速关窗"))
        val long = RuleShareFiles.slug("Очень длинное название автоматизации для проверки длины")
        assertTrue(long.length <= 40)
        assertFalse(long.endsWith("_"))
    }

    @Test fun `file name collision appends _2 then _3`() {
        val dir = tmp.newFolder("Download")
        val rule = SharedRule.fromEntity(sourceEntity())
        val first = RuleShareFiles.writeTo(dir, rule, "x")
        val second = RuleShareFiles.writeTo(dir, rule, "x")
        val third = RuleShareFiles.writeTo(dir, rule, "x")
        assertEquals("bydmate_rule_dom_zvonok.json", first.name)
        assertEquals("bydmate_rule_dom_zvonok_2.json", second.name)
        assertEquals("bydmate_rule_dom_zvonok_3.json", third.name)
    }

    @Test fun `list returns only share files, newest first`() {
        val dir = tmp.newFolder("Download")
        val old = java.io.File(dir, "bydmate_rule_a.json").apply { writeText("{}"); setLastModified(1_000_000L) }
        val new = java.io.File(dir, "bydmate_rule_b.json").apply { writeText("{}"); setLastModified(2_000_000L) }
        java.io.File(dir, "bydmate_backup_1.zip").writeText("x")
        java.io.File(dir, "notes.json").writeText("x")
        assertEquals(listOf(new, old), RuleShareFiles.listRuleFiles(dir))
    }

    // --- Import ---

    @Test fun `export then parse keeps the rule minus private fields`() {
        val parsed = RuleShare.parse(export().toString(), "Звонок") as RuleParseResult.Ok
        val rule = parsed.rule
        assertEquals("Дом: звонок", rule.name)
        assertEquals(2, rule.triggers.size)
        assertNull(rule.triggers[0].placeId)
        assertEquals("Звонок", rule.actions[0].displayName)
        assertEquals(listOf(0), rule.unresolvedPlaceIndexes())
        assertEquals(listOf(0), rule.unresolvedCallIndexes())
    }

    @Test fun `unknown place stays unresolved and the rule is added disabled`() {
        val parsed = RuleShare.parse(fixture("bydmate_rule_dacha.json"), "Звонок") as RuleParseResult.Ok
        val places = listOf(PlaceEntity(id = 3, name = "Дом", lat = 54.0, lon = 27.0))
        val rule = RuleShare.resolvePlaces(parsed.rule, places, "Въезд в", "Выезд из")
        assertEquals(listOf(0), rule.unresolvedPlaceIndexes())
        assertEquals("Дача", rule.triggers[0].placeName)
        assertFalse(RuleShare.toEntity(rule, rule.name, enableNow = true).enabled)
    }

    @Test fun `place found by name ignoring case is linked`() {
        val parsed = RuleShare.parse(fixture("bydmate_rule_dacha.json"), "Звонок") as RuleParseResult.Ok
        val places = listOf(PlaceEntity(id = 9, name = "дача ", lat = 54.0, lon = 27.0))
        val rule = RuleShare.resolvePlaces(parsed.rule, places, "Въезд в", "Выезд из")
        assertTrue(rule.unresolvedPlaceIndexes().isEmpty())
        assertEquals(9L, rule.triggers[0].placeId)
        assertEquals("Въезд в «дача »", rule.triggers[0].displayName)
    }

    @Test fun `fully resolved rule is enabled only when asked`() {
        val parsed = RuleShare.parse(fixture("bydmate_rule_speed.json"), "Звонок") as RuleParseResult.Ok
        assertFalse(parsed.rule.hasUnresolved())
        assertTrue(RuleShare.toEntity(parsed.rule, "Navi", enableNow = true).enabled)
        assertFalse(RuleShare.toEntity(parsed.rule, "Navi", enableNow = false).enabled)
        assertEquals(0, RuleShare.toEntity(parsed.rule, "Navi", enableNow = true).triggerCount)
    }

    @Test fun `unknown action kind is refused as a newer version`() {
        assertEquals(RuleParseResult.NewerVersion, RuleShare.parse(fixture("bydmate_rule_newer_kind.json"), "Звонок"))
    }

    @Test fun `unknown trigger kind and newer format version are refused`() {
        val newerTrigger = fixture("bydmate_rule_speed.json")
            .replace(""""displayName":"Скорость","kind":"param"""", """"displayName":"Скорость","kind":"gravity"""")
        assertEquals(RuleParseResult.NewerVersion, RuleShare.parse(newerTrigger, "Звонок"))
        val newerVersion = fixture("bydmate_rule_speed.json").replace("\"version\": 1", "\"version\": 2")
        assertEquals(RuleParseResult.NewerVersion, RuleShare.parse(newerVersion, "Звонок"))
    }

    @Test fun `toggle on an unknown target is refused as a newer version`() {
        val json = fixture("bydmate_rule_speed.json").replace(
            """{"command":"车窗关闭","displayName":"Закрыть все окна","kind":"param"}""",
            """{"command":"","displayName":"x","kind":"toggle","payload":"warp_drive"}""",
        )
        assertEquals(RuleParseResult.NewerVersion, RuleShare.parse(json, "Звонок"))
    }

    @Test fun `garbage and foreign json are invalid`() {
        assertEquals(RuleParseResult.Invalid, RuleShare.parse("not json", "Звонок"))
        assertEquals(RuleParseResult.Invalid, RuleShare.parse("""{"format":"other","version":1}""", "Звонок"))
        assertEquals(RuleParseResult.Invalid, RuleShare.parse("""{"format":"bydmate_rule","version":1}""", "Звонок"))
    }

    @Test fun `name collision appends the import suffix`() {
        assertEquals("Navi (импорт)", RuleShare.uniqueName("Navi", listOf("navi"), "импорт"))
        assertEquals("Navi (импорт 2)", RuleShare.uniqueName("Navi", listOf("Navi", "Navi (импорт)"), "импорт"))
        assertEquals("Navi", RuleShare.uniqueName("Navi", listOf("Other"), "импорт"))
    }

    @Test fun `every kind the editor can create is known`() {
        val created = listOf(
            TriggerDef("Speed", "车速", ">", "0", "x").kind,
            "place_enter", "place_exit", "time_of_day", "time_range", "service_start",
            "network_available", "button_press", "steering_key", "voice",
        )
        assertTrue(RuleShare.KNOWN_TRIGGER_KINDS.containsAll(created))
        assertTrue(ActionDef("c", "x").kind in RuleShare.KNOWN_ACTION_KINDS)
    }
}
