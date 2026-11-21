package com.bydmate.app.split

import com.bydmate.app.split.AppSetStore.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSetStoreTest {

    private val tiktokMusic = AppSet("set1", "Открой комплект 1", "com.zhiliaoapp.musically", "ru.yandex.music")

    private fun store(backing: MutableMap<String, Any?> = mutableMapOf()) = AppSetStore(fakeSplitSharedPrefs(backing))

    @Test fun `a saved set survives a new process`() {
        val backing = mutableMapOf<String, Any?>()
        store(backing).upsert(tiktokMusic)
        assertEquals(listOf(tiktokMusic), store(backing).sets.value)
    }

    @Test fun `a set without a right app and with autostart round-trips`() {
        val backing = mutableMapOf<String, Any?>()
        val solo = AppSet("set2", "Видео", "com.zhiliaoapp.musically", null, autostart = true)
        store(backing).upsert(solo)
        assertEquals(solo, store(backing).get("set2"))
    }

    @Test fun `unreadable stored sets start empty instead of crashing`() {
        val s = store(mutableMapOf(AppSetStore.KEY to "{not json"))
        assertTrue(s.sets.value.isEmpty())
    }

    private fun twoSets() = store().apply {
        upsert(tiktokMusic.copy(name = "тиквейз"))
        upsert(AppSet("set2", "кино", "com.google.android.youtube", null))
    }

    @Test fun `split plus the name opens that set`() {
        val s = twoSets()
        assertEquals("set1", s.resolve("сплит тиквейз")?.id)
        assertEquals("set2", s.resolve("включи сплит кино")?.id)
        assertEquals("set2", s.resolve("эй сплит кино пожалуйста")?.id)
    }

    @Test fun `a name the recognizer wrote a little differently is still found`() {
        val s = twoSets()
        assertEquals("set1", s.resolve("сплит тик вейз")?.id)
        assertEquals("set1", s.resolve("сплит тиквейс")?.id)
        assertEquals("set2", s.resolve("сплит кина")?.id)
    }

    @Test fun `a word that is no name opens nothing when there are several sets`() {
        assertNull(twoSets().resolve("сплит погода"))
        assertNull(twoSets().resolve("сплит футбол"))
    }

    @Test fun `with one set whatever follows split is that set`() {
        val s = store().apply { upsert(tiktokMusic.copy(name = "тиквейз")) }
        assertEquals("set1", s.resolve("сплит чтото непонятное")?.id)
    }

    @Test fun `split alone and the screen phrases open the first set`() {
        val s = twoSets()
        listOf("сплит", "включи сплит", "открой сплит", "разделение экрана", "включи разделение экрана",
            "открой разделение экрана", "запусти разделение экрана", "раздели экран").forEach {
            assertEquals(it, "set1", s.resolve(it)?.id)
        }
        assertEquals("set1", s.defaultSet()?.id)
    }

    @Test fun `the sets are the split in use only on Android 10 with 1-1 on`() {
        assertTrue(appSetsActive(legacyHeadUnit = true, setsOn = true))
        assertFalse(appSetsActive(legacyHeadUnit = true, setsOn = false))
        assertFalse(appSetsActive(legacyHeadUnit = false, setsOn = true))
    }

    @Test fun `a 1-2-5 head unit on the sets gets 1-1 on, the author's split stays off`() {
        val s = AppSetStore(fakeSplitSharedPrefs(mutableMapOf())).apply { upsert(tiktokMusic) }
        val split = FakeSplit(enabled = false)

        s.migrateSetsMode(split)

        assertTrue(s.setsMode.value)
        assertTrue(s.setsOn.value)
        assertFalse(split.enabled)
        assertFalse(split.native)
    }

    @Test fun `the first 1-2-6 build's author flag for 1-1 is switched back off, once`() {
        val backing = mutableMapOf<String, Any?>(AppSetStore.KEY_SETS_MODE to true)
        val s = AppSetStore(fakeSplitSharedPrefs(backing)).apply { upsert(tiktokMusic) }
        val split = FakeSplit(enabled = true, native = true)
        var flagReset = 0

        s.migrateSetsMode(split) { flagReset++ }

        assertTrue(s.setsOn.value)
        assertFalse(split.enabled)
        assertEquals("the freeform flag goes back as the switch-off does", 1, flagReset)
        split.enabled = true
        s.migrateSetsMode(split)
        assertTrue("runs once", split.enabled)
    }

    @Test fun `a head unit on the split pair keeps it`() {
        val s = AppSetStore(fakeSplitSharedPrefs(mutableMapOf())).apply { upsert(tiktokMusic) }
        val split = FakeSplit(enabled = true)

        s.migrateSetsMode(split)

        assertFalse(s.setsMode.value)
        assertFalse(s.setsOn.value)
        assertTrue(split.enabled)
    }

    private class FakeSplit(var enabled: Boolean, var native: Boolean = false) : SplitPreferences {
        override fun getLastPair(): SplitPair? = null
        override fun saveLastPair(pair: SplitPair) = Unit
        override fun clearLastPair() = Unit
        override fun isFeatureEnabled() = enabled
        override fun setFeatureEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun isNativeModeEnabled() = native
        override fun setNativeModeEnabled(enabled: Boolean) { native = enabled }
    }

    @Test fun `the bare name never opens a set, it would clash with open-app commands`() {
        val s = store().apply { upsert(tiktokMusic.copy(name = "тикток")) }
        assertNull(s.resolve("тикток"))
        assertNull(s.resolve("открой тикток"))
    }

    @Test fun `other utterances and an empty list open nothing`() {
        assertNull(twoSets().resolve("открой окно"))
        assertNull(twoSets().resolve("сплитер тиквейз"))
        assertNull(store().resolve("сплит"))
        assertNull(store().resolve("включи разделение экрана"))
    }

    @Test fun `two sets equally close to the spoken name open neither`() {
        val s = store().apply {
            upsert(AppSet("set1", "кино", "pkg.a"))
            upsert(AppSet("set2", "кина", "pkg.b"))
        }
        assertNull(s.resolve("сплит кинс"))
    }

    @Test fun `edit distance counts the letters that differ`() {
        assertEquals(0, AppSetStore.editDistance("кино", "кино"))
        assertEquals(1, AppSetStore.editDistance("кино", "кина"))
        assertEquals(3, AppSetStore.editDistance("", "кин"))
        assertEquals(4, AppSetStore.editDistance("тиквейз", "тикток"))
    }

    @Test fun `only one set starts with the service`() {
        val s = store()
        s.upsert(tiktokMusic.copy(autostart = true))
        s.upsert(AppSet("set2", "Второй", "org.telegram.messenger", null, autostart = true))
        assertEquals(listOf(false, true), s.sets.value.map { it.autostart })
        assertEquals("set2", s.autostartSet()?.id)
    }

    @Test fun `saving an existing set keeps its place`() {
        val s = store()
        s.upsert(tiktokMusic)
        s.upsert(AppSet("set2", "Второй", "org.telegram.messenger"))
        s.upsert(tiktokMusic.copy(name = "Первый"))
        assertEquals(listOf("Первый", "Второй"), s.sets.value.map { it.name })
    }

    @Test fun `removing a set forgets it`() {
        val backing = mutableMapOf<String, Any?>()
        store(backing).apply { upsert(tiktokMusic); remove("set1") }
        assertTrue(store(backing).sets.value.isEmpty())
    }

    @Test fun `new ids do not repeat after a removal`() {
        val s = store()
        s.upsert(tiktokMusic)
        s.upsert(AppSet(s.newId(), "Второй", "org.telegram.messenger"))
        s.remove("set1")
        assertEquals("set3", s.newId())
    }

    @Test fun `check accepts a good set and names what is wrong with a bad one`() {
        val s = store().apply { upsert(tiktokMusic) }
        assertEquals(Check.Ok, s.check(tiktokMusic))
        assertEquals(Check.NoName, s.check(tiktokMusic.copy(name = "  ")))
        assertEquals(Check.NameTooLong, s.check(tiktokMusic.copy(name = "а".repeat(AppSetStore.MAX_NAME_CHARS + 1))))
        assertEquals(Check.NoLeftApp, s.check(tiktokMusic.copy(leftPkg = "")))
        assertEquals(Check.SameApp, s.check(tiktokMusic.copy(rightPkg = tiktokMusic.leftPkg)))
    }

    @Test fun `a name that is another sets phrase is refused, however it is written`() {
        val s = store().apply { upsert(tiktokMusic) }
        val other = AppSet("set2", "открой  КОМПЛЕКТ 1!", "org.telegram.messenger")
        assertEquals(Check.NameInUse("Открой комплект 1"), s.check(other))
        assertFalse(s.check(other) == Check.Ok)
    }

    @Test fun `the number of sets is limited`() {
        val s = store()
        repeat(AppSetStore.MAX_SETS) { s.upsert(AppSet("set${it + 1}", "Комплект ${it + 1}", "pkg.a$it")) }
        assertEquals(Check.TooMany, s.check(AppSet("set99", "Лишний", "pkg.extra")))
        assertEquals(Check.Ok, s.check(s.sets.value.first()))
    }
}
