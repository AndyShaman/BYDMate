package com.bydmate.app.ui.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.util.appLocalizedContext
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Spot-check of the DM-i (#184) strings: a missing translation would silently fall back to Russian. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HybridStringsI18nTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun get(l: String, id: Int): String {
        LocalePreferences(ctx).setLanguage(l)
        return ctx.appLocalizedContext().getString(id)
    }

    @Test fun range_and_fuel_card_labels_are_translated() {
        assertEquals("на батарее", get("ru", R.string.dashboard_range_label_battery))
        assertEquals("on battery", get("en", R.string.dashboard_range_label_battery))
        assertEquals("纯电续航", get("zh", R.string.dashboard_range_label_battery))
        assertEquals("бак", get("ru", R.string.dashboard_fuel_tank))
        assertEquals("tank", get("en", R.string.dashboard_fuel_tank))
        assertEquals("на топливе", get("ru", R.string.dashboard_fuel_range))
        assertEquals("on fuel", get("en", R.string.dashboard_fuel_range))
    }

    @Test fun price_setting_is_translated() {
        assertEquals("Цена литра", get("ru", R.string.settings_fuel_price_label))
        assertEquals("Price per litre", get("en", R.string.settings_fuel_price_label))
        assertEquals("每升油价", get("zh", R.string.settings_fuel_price_label))
    }
}
