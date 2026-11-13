package com.bydmate.app.camera

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BlindSpotPreferencesMaxShowTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store(value: Int) {
        context.getSharedPreferences(BlindSpotPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(BlindSpotPreferences.KEY_MAX_SHOW_SEC, value).commit()
    }

    @Test fun `nothing stored means no limit`() {
        context.getSharedPreferences(BlindSpotPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        assertEquals(0, BlindSpotPreferences(context).maxShowSec)
    }

    @Test fun `a stored value is returned as is`() {
        store(15)
        assertEquals(15, BlindSpotPreferences(context).maxShowSec)
    }

    @Test fun `a stored value outside the range is coerced into it`() {
        store(500)
        assertEquals(BlindSpotPreferences.MAX_MAX_SHOW_SEC, BlindSpotPreferences(context).maxShowSec)
        store(-3)
        assertEquals(BlindSpotPreferences.MIN_MAX_SHOW_SEC, BlindSpotPreferences(context).maxShowSec)
    }
}
