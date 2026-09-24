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

    /**
     * The stored rule from `source_rule.json`: a place trigger, a call, an agent query, and a url
     * with keys that the voice agent also copied into the display name.
     */
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
        assertEquals("https://example.com/path?q=home", payload.getString("url"))
        assertFalse(payload.getBoolean("minimize"))
        assertFalse(payload.has("contactRequired"))
    }

    @Test fun `url display name is rebuilt and no secret is left anywhere in the file`() {
        val url = export().getJSONObject("rule").getJSONArray("actions").getJSONObject(2)
        assertEquals("https://example.com/path?q=home", url.getString("displayName"))
        val text = RuleShare.exportJson(SharedRule.fromEntity(sourceEntity()), "3.18.0")
        listOf("user:", "secret", "abc123", "access_token", "api_key", "zzz", "#top").forEach {
            assertFalse(it, text.contains(it))
        }
    }

    @Test fun `url query names are decoded before matching and the fragment is dropped`() {
        assertEquals("https://a.b/c?q=1", RuleShareUrl.strip("https://a.b/c?%74oken=s1&q=1").url)
        assertEquals("https://a.b/c?q=1", RuleShareUrl.strip("https://a.b/c?q=1&API%5FKEY=s2").url)
        assertEquals("https://a.b/c", RuleShareUrl.strip("https://a.b/c#access_token=s3").url)
        assertEquals("https://a.b/c", RuleShareUrl.strip("https://u%40x:p@a.b/c").url)
        assertEquals("file:///storage/emulated/0/Download/x.mp3", RuleShareUrl.strip("file:///storage/emulated/0/Download/x.mp3").url)
        assertEquals("mailto:a@b.c?subject=hi", RuleShareUrl.strip("mailto:a@b.c?subject=hi&token=s4").url)
    }

    @Test fun `intent link keeps its intent and loses credential extras`() {
        assertEquals(
            "intent://open#Intent;scheme=myapp;package=com.x;end",
            RuleShareUrl.strip("intent://open#Intent;scheme=myapp;package=com.x;S.token=s5;end").url,
        )
    }

    @Test fun `unparsable url is emptied and has to be entered again`() {
        listOf("https://user:pw@a.b/my file?token=s6#x", "javascript:fetch('/x?key=s7')", "data:text/plain,key=s8").forEach {
            val stripped = RuleShareUrl.strip(it)
            assertEquals(it, "", stripped.url)
            assertTrue(it, stripped.urlRequired)
            assertFalse(it, stripped.contactRequired)
        }
        val rule = SharedRule.fromEntity(sourceEntity()).copy(
            actions = listOf(ActionDef("", "https://a.b/my file?token=s6", "url", """{"url":"https://a.b/my file?token=s6","minimize":true}""")),
        )
        val text = RuleShare.exportJson(rule, "x")
        assertFalse(text.contains("s6"))
        assertFalse(text.contains("my file"))
        val parsed = (RuleShare.parse(text, "Звонок") as RuleParseResult.Ok).rule
        val payload = JSONObject(parsed.actions[0].payload!!)
        assertEquals("", payload.getString("url"))
        assertTrue(payload.getBoolean("urlRequired"))
        assertTrue(payload.getBoolean("minimize"))
        assertEquals(listOf(0), parsed.unresolvedUrlIndexes())
        assertTrue(parsed.hasUnresolved())
        assertFalse(RuleShare.toEntity(parsed, "x", enableNow = true).enabled)
    }

    @Test fun `query names are matched by credential substrings`() {
        assertEquals("https://h/p?q=1", RuleShareUrl.strip("https://h/p?access_key=S&q=1").url)
        listOf("apikey", "x-auth", "passwd", "Signature", "client_secret", "API").forEach {
            assertEquals(it, "https://h/p?q=1", RuleShareUrl.strip("https://h/p?$it=S&q=1").url)
        }
        val rule = SharedRule.fromEntity(sourceEntity()).copy(
            actions = listOf(ActionDef("", "https://h/p?access_key=S3CR3T", "url", """{"url":"https://h/p?access_key=S3CR3T&q=1","minimize":false}""")),
        )
        val text = RuleShare.exportJson(rule, "x")
        assertFalse(text.contains("S3CR3T"))
        assertFalse(text.contains("access_key"))
        assertTrue(text.contains("https://h/p?q=1"))
    }

    @Test fun `sms keeps its body and loses only the number`() {
        val stripped = RuleShareUrl.strip("sms:+375291234567?body=hello%20there&token=s9")
        assertEquals("sms:?body=hello%20there", stripped.url)
        assertTrue(stripped.contactRequired)
        assertEquals("smsto:?body=hi", RuleShareUrl.strip("smsto:375291234567?body=hi").url)
        assertEquals("tel:", RuleShareUrl.strip("tel:+375291234567?x=1").url)
        assertEquals("sms:+375290000000?body=hello%20there", RuleShareUrl.withNumber(stripped.url, " +375290000000 "))
        assertEquals("tel:+375290000000", RuleShareUrl.withNumber("tel:", "+375290000000"))
    }

    @Test fun `tel and sms links lose the number and need a contact`() {
        listOf("tel:+375291234567", "TEL:+375 29 123-45-67", "sms:+375291234567?body=hi", "smsto:375291234567").forEach {
            val stripped = RuleShareUrl.strip(it)
            assertTrue(it, stripped.contactRequired)
            assertFalse(it, stripped.url.any(Char::isDigit))
        }
        val rule = SharedRule.fromEntity(sourceEntity()).let { r ->
            r.copy(
                triggers = listOf(TriggerDef(param = "speed", chineseName = "", operator = ">", value = "7", displayName = "")),
                actions = listOf(ActionDef("", "tel:+375291234567", "url", """{"url":"tel:+375291234567","minimize":true}""")),
            )
        }
        val text = RuleShare.exportJson(rule, "x")
        assertFalse(text.contains("375291234567"))
        val parsed = (RuleShare.parse(text, "Звонок") as RuleParseResult.Ok).rule
        assertEquals(listOf(0), parsed.unresolvedCallIndexes())
        assertEquals("tel:", JSONObject(parsed.actions[0].payload!!).getString("url"))
        assertTrue(JSONObject(parsed.actions[0].payload!!).getBoolean("contactRequired"))
        assertEquals(listOf<Int>(), parsed.unresolvedPlaceIndexes())
        assertFalse(RuleShare.toEntity(parsed, "x", enableNow = true).enabled)
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
            RuleShareUrl.strip("yandexmusic://radio/user/onyourwave?play=true").url)
        assertEquals("https://a.b/c", RuleShareUrl.strip("https://a.b/c?token=1").url)
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

    @Test fun `export leaves no temp file and a failed write leaves nothing importable`() {
        val dir = tmp.newFolder("Download")
        val rule = SharedRule.fromEntity(sourceEntity())
        val file = RuleShareFiles.writeTo(dir, rule, "x")
        assertEquals(listOf(file.name), dir.list()!!.toList())
        assertTrue(RuleShare.parse(file.readText(), "Звонок") is RuleParseResult.Ok)

        val readOnly = tmp.newFolder("ReadOnly")
        assertTrue(readOnly.setWritable(false))
        try {
            RuleShareFiles.writeTo(readOnly, rule, "x")
            org.junit.Assert.fail("write into a read-only folder must fail")
        } catch (expected: java.io.IOException) {
            assertTrue(readOnly.list()!!.isEmpty())
        } finally {
            readOnly.setWritable(true)
        }
    }

    @Test fun `a temp file left behind is never listed for import`() {
        val dir = tmp.newFolder("Download")
        java.io.File(dir, ".bydmate_rule_x.json.tmp").writeText("{")
        assertTrue(RuleShareFiles.listRuleFiles(dir).isEmpty())
    }

    @Test fun `read stops at 256 KiB`() {
        val dir = tmp.newFolder("Download")
        val small = java.io.File(dir, "bydmate_rule_small.json").apply { writeText(fixture("bydmate_rule_speed.json")) }
        assertEquals(fixture("bydmate_rule_speed.json"), RuleShareFiles.readLimited(small))
        val exact = java.io.File(dir, "bydmate_rule_exact.json").apply { writeBytes(ByteArray(RuleShareFiles.MAX_FILE_BYTES) { 'a'.code.toByte() }) }
        assertEquals(RuleShareFiles.MAX_FILE_BYTES, RuleShareFiles.readLimited(exact)!!.length)
        val big = java.io.File(dir, "bydmate_rule_big.json").apply { writeBytes(ByteArray(RuleShareFiles.MAX_FILE_BYTES + 1) { 'a'.code.toByte() }) }
        assertNull(RuleShareFiles.readLimited(big))
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

    @Test fun `place name match ignores extra whitespace`() {
        val parsed = RuleShare.parse(fixture("bydmate_rule_dacha.json").replace("\"placeName\":\"Дача\"", "\"placeName\":\" Моя   дача \""), "Звонок") as RuleParseResult.Ok
        val places = listOf(PlaceEntity(id = 4, name = "моя дача", lat = 54.0, lon = 27.0))
        assertEquals(4L, RuleShare.resolvePlaces(parsed.rule, places, "Въезд в", "Выезд из").triggers[0].placeId)
    }

    @Test fun `two places with the same name are left for the user to pick`() {
        val parsed = RuleShare.parse(fixture("bydmate_rule_dacha.json"), "Звонок") as RuleParseResult.Ok
        val places = listOf(
            PlaceEntity(id = 1, name = "Дача", lat = 54.0, lon = 27.0),
            PlaceEntity(id = 2, name = "дача", lat = 55.0, lon = 28.0),
        )
        val rule = RuleShare.resolvePlaces(parsed.rule, places, "Въезд в", "Выезд из")
        assertEquals(listOf(0), rule.unresolvedPlaceIndexes())
        assertNull(rule.triggers[0].placeId)
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

    @Test fun `json nested past the limit inside a string is invalid`() {
        val deep = fixture("bydmate_rule_deep.json")
        assertEquals(RuleParseResult.Invalid, RuleShare.parse(deep, "Звонок"))
        // The same file five levels deep is a normal rule.
        val shallow = deep.replace("[".repeat(40), "[".repeat(5)).replace("]".repeat(40), "]".repeat(5))
        assertTrue(RuleShare.parse(shallow, "Звонок") is RuleParseResult.Ok)
    }

    @Test fun `json nested past the limit in the file itself is invalid`() {
        val body = fixture("bydmate_rule_speed.json")
        val deep = body.replace("\"play_sound\": false", "\"play_sound\": false, \"x\": " + "[".repeat(40) + "]".repeat(40))
        assertEquals(RuleParseResult.Invalid, RuleShare.parse(deep, "Звонок"))
        // A quote inside an unquoted literal does not open a string for the parser, nor for the limit.
        val hidden = body.replace("\"play_sound\": false", "\"play_sound\": false, \"x\": [x',"  + "[".repeat(40) + "]".repeat(40) + "]")
        assertEquals(RuleParseResult.Invalid, RuleShare.parse(hidden, "Звонок"))
        // The file root and "rule" are two levels: the array may take the remaining ones, not one more.
        val room = RuleShareJsonLimits.MAX_DEPTH - 2
        val ok = body.replace("\"play_sound\": false", "\"play_sound\": false, \"x\": " + "[".repeat(room) + "]".repeat(room))
        assertTrue(RuleShare.parse(ok, "Звонок") is RuleParseResult.Ok)
        val over = body.replace("\"play_sound\": false", "\"play_sound\": false, \"x\": " + "[".repeat(room + 1) + "]".repeat(room + 1))
        assertEquals(RuleParseResult.Invalid, RuleShare.parse(over, "Звонок"))
    }

    @Test fun `a string longer than 8 KiB is invalid`() {
        val body = fixture("bydmate_rule_speed.json")
        val long = body.replace("\"Navi\"", "\"" + "x".repeat(RuleShareJsonLimits.MAX_STRING_CHARS + 1) + "\"")
        assertEquals(RuleParseResult.Invalid, RuleShare.parse(long, "Звонок"))
        val fits = body.replace("\"Navi\"", "\"" + "x".repeat(RuleShareJsonLimits.MAX_STRING_CHARS) + "\"")
        assertTrue(RuleShare.parse(fits, "Звонок") is RuleParseResult.Ok)
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
