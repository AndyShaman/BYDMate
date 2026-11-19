package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rule editor's picker offers «Назад» and «Домой»: payload-free kinds, labelled like the import preview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class GoBackActionBuilderTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `the picker action is payload-free kind go_back`() {
        val a = newGoBackAction(ctx)
        assertEquals("go_back", a.kind)
        assertNull(a.payload)
        assertEquals("", a.command)
        assertEquals(ctx.getString(R.string.automation_action_go_back), actionText(a, ctx))
    }

    @Test fun `the picker home action is payload-free kind go_home`() {
        val a = newGoHomeAction(ctx)
        assertEquals("go_home", a.kind)
        assertNull(a.payload)
        assertEquals("", a.command)
        assertEquals(ctx.getString(R.string.widget_button_icon_home), actionText(a, ctx))
    }
}
