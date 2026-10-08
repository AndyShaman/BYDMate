package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.util.appLocalizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the level rows, their picker tiles, the card and the import summary read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LevelFamilyTextTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun lc(lang: String): Context {
        LocalePreferences(ctx).setLanguage(lang)
        return ctx.appLocalizedContext()
    }

    @Test fun `picker tiles read as the mock, ru`() {
        val lc = lc("ru")
        val expected = mapOf(
            LevelFamily.TEMPERATURE to "Температура 16–33 °C",
            LevelFamily.FAN to "Вентилятор 1–7",
            LevelFamily.SEAT_HEAT_DRIVER to "Подогрев сиденья водителя выкл, 1–5",
            LevelFamily.SEAT_HEAT_PASSENGER to "Подогрев сиденья пассажира выкл, 1–5",
            LevelFamily.SEAT_VENT_DRIVER to "Вентиляция сиденья водителя выкл, 1–5",
            LevelFamily.SEAT_VENT_PASSENGER to "Вентиляция сиденья пассажира выкл, 1–5",
            LevelFamily.WINDOW_DRIVER to "Окно водителя: положение 0–100 %",
            LevelFamily.WINDOW_PASSENGER to "Окно пассажира: положение 0–100 %",
            LevelFamily.WINDOW_REAR_LEFT to "Заднее левое окно: положение 0–100 %",
            LevelFamily.WINDOW_REAR_RIGHT to "Заднее правое окно: положение 0–100 %",
            LevelFamily.FRIDGE_COOL to "Холодильник: охлаждение −6…+6 °C",
            LevelFamily.FRIDGE_HEAT to "Холодильник: обогрев 35–50 °C",
        )
        for ((f, text) in expected) assertEquals(text, lc.getString(f.tileRes))
    }

    @Test fun `display names carry the value, off as a word`() {
        val lc = lc("ru")
        assertEquals("Температура: 28 °C", LevelFamily.TEMPERATURE.displayName(28, lc))
        assertEquals("Вентилятор: 4", LevelFamily.FAN.displayName(4, lc))
        assertEquals("Подогрев сиденья водителя: 3", LevelFamily.SEAT_HEAT_DRIVER.displayName(3, lc))
        assertEquals("Подогрев сиденья пассажира: выкл", LevelFamily.SEAT_HEAT_PASSENGER.displayName(0, lc))
        assertEquals("Окно водителя: 30 %", LevelFamily.WINDOW_DRIVER.displayName(30, lc))
        assertEquals("Охлаждение холодильника: −3 °C", LevelFamily.FRIDGE_COOL.displayName(-3, lc))
        assertEquals("Обогрев холодильника: 40 °C", LevelFamily.FRIDGE_HEAT.displayName(40, lc))
    }

    @Test fun `every locale has its own name and tile`() {
        val ru = lc("ru")
        val ruTexts = LevelFamily.entries.map { ru.getString(it.nameRes) to ru.getString(it.tileRes) }
        for (lang in listOf("be", "en", "pl", "pt", "zh")) {
            val l = lc(lang)
            LevelFamily.entries.forEachIndexed { i, f ->
                assertNotEquals("$lang name $f", ruTexts[i].first, l.getString(f.nameRes))
                assertNotEquals("$lang tile $f", ruTexts[i].second, l.getString(f.tileRes))
            }
        }
    }

    // A row's dropdown and the picker come from one builder: each family once, in its category.
    @Test fun `every family is offered once in the dropdown and once in the picker`() {
        val lc = lc("ru")
        val entries = catalogEntries(lc)
        val sections = catalogSections(lc)
        for (f in LevelFamily.entries) {
            val tile = lc.getString(f.tileRes)
            assertEquals("dropdown $f", 1, entries.count { it.label == tile })
            assertEquals("dropdown category $f", f.categoryRes, entries.single { it.label == tile }.categoryRes)
            assertEquals("picker $f", 1, sections.sumOf { (_, tiles) -> tiles.count { it.label == tile } })
            assertEquals("picker category $f", 1, sections.single { it.first == f.categoryRes }.second.count { it.label == tile })
            assertEquals("makes $f", f.newAction(ctx), entries.single { it.label == tile }.make(ctx))
        }
        // Same order and labels inside every picker section as in the dropdown.
        for ((cat, tiles) in sections) {
            assertEquals(entries.filter { it.categoryRes == cat }.map { it.label }, tiles.map { it.label })
        }
    }

    @Test fun `a family command shows its name in the editor language`() {
        lc("en")
        assertEquals("Temperature: 22 °C", paramSelectedText(ActionDef("设置温度22", "Температура: 22 °C"), ctx))
        assertEquals("Open Driver Window", paramSelectedText(ActionDef("主驾打开100", "x"), ctx))
        assertEquals("stale", paramSelectedText(ActionDef("未知", "stale"), ctx))
    }

    @Test fun `a picked tile stores the default value`() {
        lc("ru")
        assertEquals(ActionDef("设置温度22", "Температура: 22 °C"), LevelFamily.TEMPERATURE.newAction(ctx))
        assertEquals(ActionDef("主驾打开50", "Окно водителя: 50 %"), LevelFamily.WINDOW_DRIVER.newAction(ctx))
    }

    @Test fun `saved, card and import name a level action by its family`() {
        lc("ru")
        val old = ActionDef("设置温度22", "Темп. 22°C")
        assertEquals("Температура: 22 °C", withCatalogName(old, ctx).displayName)
        assertEquals("Подогрев сиденья водителя: выкл", withCatalogName(ActionDef("主驾座椅加热关闭", "x"), ctx).displayName)
        assertEquals("Окно водителя: 30 %", actionText(ActionDef("主驾打开30", "x"), ctx.appLocalizedContext()))
        // A command that still has its own tile keeps the tile's name.
        assertEquals("Открыть окно водителя", withCatalogName(ActionDef("主驾打开100", "x"), ctx).displayName)
        assertEquals(
            "Команда автомобилю: Температура: 22 °C (设置温度22)",
            RuleImportSummary.action(old, ctx),
        )
    }
}
