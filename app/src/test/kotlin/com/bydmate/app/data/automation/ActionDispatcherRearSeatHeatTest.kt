package com.bydmate.app.data.automation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.vehicle.VehicleWriteError
import com.bydmate.app.util.AppStrings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rear seat heat (#327) in the dispatcher: its own NotEquipped wording, and no speed gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherRearSeatHeatTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After fun restoreLanguage() = LocalePreferences(app).setLanguage("ru")

    @Test fun `a car without rear heat says so, not the steering wheel`() {
        LocalePreferences(app).setLanguage("ru")
        val err = VehicleWriteError.NotEquipped("rear_left_seat_heat")
        assertEquals(
            "На этой машине нет подогрева задних сидений",
            ActionDispatcher.paramFailureReason(err, AppStrings(app)),
        )
    }

    @Test fun `rear seat heat is not speed gated`() {
        assertFalse(ActionDispatcher.isSpeedGated("后左座椅加热2档"))
    }
}
