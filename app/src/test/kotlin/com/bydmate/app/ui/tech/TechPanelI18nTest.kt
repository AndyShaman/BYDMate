package com.bydmate.app.ui.tech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.util.appLocalizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Spot-check that the «Техника» strings really exist in ru/en/zh — a missing translation
 * silently falls back to Russian, which the resource lookup alone would not reveal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TechPanelI18nTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun get(l: String, id: Int): String {
        LocalePreferences(ctx).setLanguage(l)
        return ctx.appLocalizedContext().getString(id)
    }
    private fun get(l: String, id: Int, vararg args: Any): String {
        LocalePreferences(ctx).setLanguage(l)
        return ctx.appLocalizedContext().getString(id, *args)
    }

    @Test fun screen_chrome_is_translated() {
        assertEquals("Техника", get("ru", R.string.tech_title))
        assertEquals("Tech", get("en", R.string.tech_title))
        assertEquals("技术", get("zh", R.string.tech_title))
        assertEquals("Машина не отдаёт эти данные", get("ru", R.string.tech_no_data))
        assertEquals("The car does not report this data", get("en", R.string.tech_no_data))
        assertEquals("车辆未提供这些数据", get("zh", R.string.tech_no_data))
        assertEquals("изоляция", get("ru", R.string.dashboard_battery_insulation_label))
        assertEquals("insulation", get("en", R.string.dashboard_battery_insulation_label))
        assertEquals("绝缘", get("zh", R.string.dashboard_battery_insulation_label))
    }

    @Test fun card_order_hint_is_translated() {
        assertEquals("Удерживайте карточку, чтобы поменять порядок", get("ru", R.string.tech_order_hint))
        assertEquals("Hold a card to change the order", get("en", R.string.tech_order_hint))
        assertEquals("长按卡片可调整顺序", get("zh", R.string.tech_order_hint))
    }

    @Test fun card_headers_are_translated() {
        assertEquals("Батарея · сейчас", get("ru", R.string.tech_card_battery_now))
        assertEquals("Battery · now", get("en", R.string.tech_card_battery_now))
        assertEquals("电池 · 当前", get("zh", R.string.tech_card_battery_now))
        assertEquals("Моторы и инверторы", get("ru", R.string.tech_card_motors))
        assertEquals("Motors and inverters", get("en", R.string.tech_card_motors))
        assertEquals("电机与逆变器", get("zh", R.string.tech_card_motors))
        assertEquals("Шины · давление / температура", get("ru", R.string.tech_card_tyres))
        assertEquals("Tyres · pressure / temperature", get("en", R.string.tech_card_tyres))
        assertEquals("轮胎 · 胎压 / 温度", get("zh", R.string.tech_card_tyres))
    }

    /** Units are format strings: a broken placeholder would throw or drop the number. */
    @Test fun value_formats_carry_their_arguments() {
        assertEquals("6,8 МОм", get("ru", R.string.tech_value_mohm, 6.8))
        assertEquals("6.8 MΩ", get("en", R.string.tech_value_mohm, 6.8))
        assertEquals("11 мВ", get("ru", R.string.tech_value_mv, 11))
        assertEquals("11 mV", get("en", R.string.tech_value_mv, 11))
        assertEquals("173 кВт", get("ru", R.string.tech_value_kw, 173))
        assertEquals("173 kW", get("zh", R.string.tech_value_kw, 173))
        assertEquals("576 Вт", get("ru", R.string.tech_value_watt, 576))
        assertEquals("2,00 %/ч", get("ru", R.string.tech_value_percent_per_hour, 2.0))
        assertEquals("2.00 %/h", get("en", R.string.tech_value_percent_per_hour, 2.0))
        assertEquals("2,52 бар · 15°", get("ru", R.string.tech_value_tyre, 2.52, 15))
        assertEquals("2.52 bar · 15°", get("en", R.string.tech_value_tyre, 2.52, 15))
    }

    /** All ten hints must be present and non-Russian in en/zh. */
    @Test fun hints_are_translated_in_en_and_zh() {
        val hints = listOf(
            R.string.tech_hint_soh, R.string.tech_hint_hv_voltage, R.string.tech_hint_power,
            R.string.tech_hint_motor_power,
            R.string.tech_hint_12v, R.string.tech_hint_insulation, R.string.tech_hint_bms_limits,
            R.string.tech_hint_cells, R.string.tech_hint_motors, R.string.tech_hint_compressor,
            R.string.tech_hint_tyres,
        )
        for (id in hints) {
            val ru = get("ru", id)
            assertTrue("empty ru hint", ru.length > 40)
            for (l in listOf("en", "zh")) {
                val t = get(l, id)
                assertTrue("empty $l hint", t.length > 10)
                assertFalse("hint not translated to $l", t == ru)
            }
        }
    }

    /** The merged-card and diagnostics strings: present everywhere, no em dash, not the Russian text. */
    @Test fun car_state_and_diagnostics_strings_are_translated() {
        val ids = listOf(
            R.string.tech_card_car_state, R.string.tech_card_diagnostics,
            R.string.tech_dtc_scan, R.string.tech_hint_diagnostics,
        )
        for (id in ids) {
            val ru = get("ru", id)
            for (l in listOf("ru", "be", "en", "pl", "pt", "zh")) {
                assertFalse("em dash in $l", get(l, id).contains("\u2014"))
            }
            for (l in listOf("be", "en", "pl", "pt", "zh")) {
                assertFalse("not translated to $l", get(l, id) == ru)
            }
        }
    }

    @Test fun a_scan_with_only_unread_codes_is_not_called_clean() {
        val unread = com.bydmate.app.data.dtc.DtcScanSummary(
            answered = 12, total = 18, now = 0, old = 0, pending = 0, found = emptyList(), unreadBlocks = 1,
        )
        val text = dtcNoRowsText(ctx.appLocalizedContext(), unread)
        assertFalse(text, text.contains("Ошибок в блоках не найдено"))
        assertTrue(text, text.contains("прочитать не удалось"))
    }

    @Test fun service_hint_names_the_menu_path_in_every_language() {
        val ru = get("ru", R.string.tech_hint_diagnostics)
        assertTrue(ru, ru.contains("Vehicle → Maintenance"))
        assertFalse(ru, ru.contains("VehicleHealth"))
        for (l in listOf("ru", "be", "en", "pl", "pt", "zh")) {
            val hint = get(l, R.string.tech_hint_diagnostics)
            assertTrue("$l: $hint", hint.contains("Maintenance Interval"))
            assertTrue("$l: $hint", hint.contains("Maintenance Mileage"))
            assertFalse("$l: $hint", hint.contains("—"))
        }
    }
}
