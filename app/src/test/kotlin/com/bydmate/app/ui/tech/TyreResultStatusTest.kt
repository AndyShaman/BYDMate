package com.bydmate.app.ui.tech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.helper.dtc.TpmsVerdict
import com.bydmate.app.util.appLocalizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** D6: the result tile text per verdict; an UNVERIFIED wheel is «read the block», never «not written». */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TyreResultStatusTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun get(l: String, id: Int): String {
        LocalePreferences(ctx).setLanguage(l)
        return ctx.appLocalizedContext().getString(id)
    }

    @Test fun `D6 an unverified wheel maps to the read-the-block text`() {
        assertEquals(R.string.tyre_status_unverified, resultStatusRes(TpmsVerdict.UNVERIFIED, heard = true))
        assertEquals(R.string.tyre_status_unverified, resultStatusRes(TpmsVerdict.UNVERIFIED, heard = false))
        assertEquals(R.string.tyre_status_unverified, resultStatusRes(TpmsVerdict.UNVERIFIED, heard = null))
    }

    @Test fun `D6 the other verdicts keep their texts`() {
        assertEquals(R.string.tyre_status_not_changed, resultStatusRes(null, heard = null))
        assertEquals(R.string.tyre_status_written, resultStatusRes(TpmsVerdict.WRITTEN, heard = true))
        assertEquals(R.string.tyre_status_written_wait, resultStatusRes(TpmsVerdict.WRITTEN, heard = false))
        assertEquals(R.string.tyre_status_in_block, resultStatusRes(TpmsVerdict.ALREADY, heard = true))
        assertEquals(R.string.tyre_status_not_written, resultStatusRes(TpmsVerdict.NOT_WRITTEN, heard = null))
        assertEquals(R.string.tyre_status_not_written, resultStatusRes(TpmsVerdict.REFUSED, heard = null))
    }

    @Test fun `D6 the text exists in every language and is not the not-written one`() {
        assertEquals("Не проверено, прочитайте блок", get("ru", R.string.tyre_status_unverified))
        for (l in listOf("ru", "en", "be", "pl", "pt", "zh")) {
            val text = get(l, R.string.tyre_status_unverified)
            assertTrue(l, text.isNotBlank())
            assertNotEquals(l, get(l, R.string.tyre_status_not_written), text)
            if (l != "ru") assertNotEquals(l, get("ru", R.string.tyre_status_unverified), text)
        }
    }
}
