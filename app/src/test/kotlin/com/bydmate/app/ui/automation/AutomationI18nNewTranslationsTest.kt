package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Additive check for the NEW en/zh translations of automation units and enum
 * labels (ru existed inline before; en/zh were Russian). GENERATED. Green only
 * after the resource migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AutomationI18nNewTranslationsTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun get(l: String, id: Int): String {
        LocalePreferences(ctx).setLanguage(l)
        return ctx.appLocalizedContext().getString(id)
    }

    @Test fun unit_translations_present() {
        assertEquals("°C", get("ru", R.string.auto_unit_celsius)); assertEquals("°C", get("en", R.string.auto_unit_celsius)); assertEquals("°C", get("zh", R.string.auto_unit_celsius))
        assertEquals("(0=сухо)", get("ru", R.string.auto_unit_dry_hint)); assertEquals("(0=dry)", get("en", R.string.auto_unit_dry_hint)); assertEquals("(0=干)", get("zh", R.string.auto_unit_dry_hint))
        assertEquals("км/ч", get("ru", R.string.auto_unit_kmh)); assertEquals("km/h", get("en", R.string.auto_unit_kmh)); assertEquals("km/h", get("zh", R.string.auto_unit_kmh))
        assertEquals("кПа", get("ru", R.string.auto_unit_kpa)); assertEquals("kPa", get("en", R.string.auto_unit_kpa)); assertEquals("kPa", get("zh", R.string.auto_unit_kpa))
        assertEquals("%", get("ru", R.string.auto_unit_percent)); assertEquals("%", get("en", R.string.auto_unit_percent)); assertEquals("%", get("zh", R.string.auto_unit_percent))
        assertEquals("% откр.", get("ru", R.string.auto_unit_percent_open)); assertEquals("% open", get("en", R.string.auto_unit_percent_open)); assertEquals("开度%", get("zh", R.string.auto_unit_percent_open))
        assertEquals("В", get("ru", R.string.auto_unit_volt)); assertEquals("V", get("en", R.string.auto_unit_volt)); assertEquals("V", get("zh", R.string.auto_unit_volt))
    }

    @Test fun enum_translations_present() {
        assertEquals("Заряжается", get("ru", R.string.auto_enum_charging)); assertEquals("Charging", get("en", R.string.auto_enum_charging)); assertEquals("充电中", get("zh", R.string.auto_enum_charging))
        assertEquals("Закрыта", get("ru", R.string.auto_enum_closed_f)); assertEquals("Closed", get("en", R.string.auto_enum_closed_f)); assertEquals("关闭", get("zh", R.string.auto_enum_closed_f))
        assertEquals("Закрыт", get("ru", R.string.auto_enum_closed_m)); assertEquals("Closed", get("en", R.string.auto_enum_closed_m)); assertEquals("关闭", get("zh", R.string.auto_enum_closed_m))
        assertEquals("D", get("ru", R.string.auto_enum_code_d)); assertEquals("D", get("en", R.string.auto_enum_code_d)); assertEquals("D", get("zh", R.string.auto_enum_code_d))
        assertEquals("DRIVE", get("ru", R.string.auto_enum_code_drive)); assertEquals("DRIVE", get("en", R.string.auto_enum_code_drive)); assertEquals("DRIVE", get("zh", R.string.auto_enum_code_drive))
        assertEquals("ECO", get("ru", R.string.auto_enum_code_eco)); assertEquals("ECO", get("en", R.string.auto_enum_code_eco)); assertEquals("ECO", get("zh", R.string.auto_enum_code_eco))
        assertEquals("N", get("ru", R.string.auto_enum_code_n)); assertEquals("N", get("en", R.string.auto_enum_code_n)); assertEquals("N", get("zh", R.string.auto_enum_code_n))
        assertEquals("NORMAL", get("ru", R.string.auto_enum_code_normal)); assertEquals("NORMAL", get("en", R.string.auto_enum_code_normal)); assertEquals("NORMAL", get("zh", R.string.auto_enum_code_normal))
        assertEquals("OFF", get("ru", R.string.auto_enum_code_off)); assertEquals("OFF", get("en", R.string.auto_enum_code_off)); assertEquals("OFF", get("zh", R.string.auto_enum_code_off))
        assertEquals("ON", get("ru", R.string.auto_enum_code_on)); assertEquals("ON", get("en", R.string.auto_enum_code_on)); assertEquals("ON", get("zh", R.string.auto_enum_code_on))
        assertEquals("P", get("ru", R.string.auto_enum_code_p)); assertEquals("P", get("en", R.string.auto_enum_code_p)); assertEquals("P", get("zh", R.string.auto_enum_code_p))
        assertEquals("R", get("ru", R.string.auto_enum_code_r)); assertEquals("R", get("en", R.string.auto_enum_code_r)); assertEquals("R", get("zh", R.string.auto_enum_code_r))
        assertEquals("SNOW", get("ru", R.string.auto_enum_code_snow)); assertEquals("SNOW", get("en", R.string.auto_enum_code_snow)); assertEquals("SNOW", get("zh", R.string.auto_enum_code_snow))
        assertEquals("SPORT", get("ru", R.string.auto_enum_code_sport)); assertEquals("SPORT", get("en", R.string.auto_enum_code_sport)); assertEquals("SPORT", get("zh", R.string.auto_enum_code_sport))
        assertEquals("Подключён", get("ru", R.string.auto_enum_connected)); assertEquals("Connected", get("en", R.string.auto_enum_connected)); assertEquals("已连接", get("zh", R.string.auto_enum_connected))
        assertEquals("Пристёгнут", get("ru", R.string.auto_enum_fastened)); assertEquals("Fastened", get("en", R.string.auto_enum_fastened)); assertEquals("已系", get("zh", R.string.auto_enum_fastened))
        assertEquals("Внешний воздух", get("ru", R.string.auto_enum_fresh_air)); assertEquals("Fresh air", get("en", R.string.auto_enum_fresh_air)); assertEquals("外循环", get("zh", R.string.auto_enum_fresh_air))
        assertEquals("Заблокирован", get("ru", R.string.auto_enum_locked)); assertEquals("Locked", get("en", R.string.auto_enum_locked)); assertEquals("已锁", get("zh", R.string.auto_enum_locked))
        assertEquals("Нет", get("ru", R.string.auto_enum_none)); assertEquals("None", get("en", R.string.auto_enum_none)); assertEquals("无", get("zh", R.string.auto_enum_none))
        assertEquals("Выкл", get("ru", R.string.auto_enum_off)); assertEquals("Off", get("en", R.string.auto_enum_off)); assertEquals("关", get("zh", R.string.auto_enum_off))
        assertEquals("Вкл", get("ru", R.string.auto_enum_on)); assertEquals("On", get("en", R.string.auto_enum_on)); assertEquals("开", get("zh", R.string.auto_enum_on))
        assertEquals("Открыта", get("ru", R.string.auto_enum_open_f)); assertEquals("Open", get("en", R.string.auto_enum_open_f)); assertEquals("打开", get("zh", R.string.auto_enum_open_f))
        assertEquals("Открыт", get("ru", R.string.auto_enum_open_m)); assertEquals("Open", get("en", R.string.auto_enum_open_m)); assertEquals("打开", get("zh", R.string.auto_enum_open_m))
        assertEquals("Внутренний воздух", get("ru", R.string.auto_enum_recirc)); assertEquals("Recirculation", get("en", R.string.auto_enum_recirc)); assertEquals("内循环", get("zh", R.string.auto_enum_recirc))
        assertEquals("Не пристёгнут", get("ru", R.string.auto_enum_unfastened)); assertEquals("Unfastened", get("en", R.string.auto_enum_unfastened)); assertEquals("未系", get("zh", R.string.auto_enum_unfastened))
        assertEquals("Разблокирован", get("ru", R.string.auto_enum_unlocked)); assertEquals("Unlocked", get("en", R.string.auto_enum_unlocked)); assertEquals("已解锁", get("zh", R.string.auto_enum_unlocked))
    }

    /**
     * Closes the en/zh wiring gap: iterates the REAL catalog and exercises the
     * actual resolvers (not raw resource ids), so a value<->res mismatch (silent
     * `?: value` fallback) or a wrong unitRes is caught in en AND zh, not just ru.
     */
    @Test fun resolvers_route_units_and_enums_through_resources_in_en_and_zh() {
        for (l in listOf("en", "zh")) {
            LocalePreferences(ctx).setLanguage(l)
            val lc = ctx.appLocalizedContext()
            for (p in TRIGGER_PARAMS) {
                p.unitRes?.let { res ->
                    assertEquals("unit wiring ${p.param} [$l]", lc.getString(res), p.localizedUnit(ctx))
                }
                p.enumValues?.forEach { (value, res) ->
                    assertEquals("enum wiring ${p.param}/$value [$l]", lc.getString(res), p.localizedEnumLabel(value, ctx))
                }
            }
        }
    }

    /**
     * Portuguese (generic values-pt) parity. Iterating the REAL TRIGGER_PARAMS +
     * ACTION_COMMANDS under "pt" proves every name/category/unit/enum/action label
     * resolves through a pt resource (wiring intact, no missing key crashes). The
     * spot-checks pin actual pt text per type so a silent Russian fallback (which a
     * missing values-pt key would produce) is caught, not masked.
     */
    @Test fun pt_catalog_fully_backed_by_resources() {
        LocalePreferences(ctx).setLanguage("pt")
        val lc = ctx.appLocalizedContext()
        for (p in TRIGGER_PARAMS) {
            assertEquals("name ${p.param}", lc.getString(p.nameRes), p.localizedName(ctx))
            assertEquals("category ${p.param}", lc.getString(p.categoryRes), p.localizedCategory(ctx))
            p.unitRes?.let { assertEquals("unit ${p.param}", lc.getString(it), p.localizedUnit(ctx)) }
            p.enumValues?.forEach { (value, res) ->
                assertEquals("enum ${p.param}/$value", lc.getString(res), p.localizedEnumLabel(value, ctx))
            }
        }
        for (a in ACTION_COMMANDS) {
            assertEquals("action name ${a.command}", lc.getString(a.nameRes), a.localizedName(ctx))
            assertEquals("action category ${a.command}", lc.getString(a.categoryRes), a.localizedCategory(ctx))
        }
        // Real pt text per type (fallback would yield the ru string instead).
        assertEquals("Clima", lc.getString(R.string.auto_cat_climate))
        assertEquals("Velocidade", lc.getString(R.string.auto_param_speed))
        assertEquals("km/h", lc.getString(R.string.auto_unit_kmh))
        assertEquals("Ligado", lc.getString(R.string.auto_enum_on))
        assertEquals("Travar portas", lc.getString(R.string.auto_act_lock_doors))
    }
}
