package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.TriggerDef
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rule card names an enum trigger value («Передача = P»), not its stored code («= 1»). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TriggerValueLabelTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() { LocalePreferences(ctx).setLanguage("ru") }

    private fun label(param: String, value: String) =
        triggerValueLabel(TriggerDef(param, "", "==", value, param), ctx)

    @Test fun `gear 1 is P`() = assertEquals("P", label("Gear", "1"))

    @Test fun `drive mode 4 is snow`() = assertEquals("Снег", label("DriveMode", "4"))

    @Test fun `turn signal codes are named`() {
        assertEquals("Левый", label("TurnSignal", "2"))
        assertEquals("Аварийка", label("TurnSignal", "6"))
    }

    @Test fun `numeric params and unknown codes stay as stored`() {
        assertEquals("15", label("SOC", "15"))
        assertEquals("9", label("Gear", "9"))
    }

    @Test fun `the name follows the interface language`() {
        LocalePreferences(ctx).setLanguage("en")
        assertEquals("SNOW", label("DriveMode", "4"))
    }
}
