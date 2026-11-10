package com.bydmate.app.data.repository

import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.FakeSettingsDao
import com.bydmate.app.data.telegram.ReportField
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// «Отчёт при выключении машины»: a choice saved before the odometer existed gets it once; after
// that the owner's own choice stands, switching it off included.
class TgReportOdometerSettingTest {

    private val dao = FakeSettingsDao()
    private val repo = SettingsRepository(dao, mockk<LocalePreferences>(relaxed = true))

    @Test fun `a choice saved before the odometer existed gets it once, then the owner decides`() = runTest {
        dao.map[SettingsRepository.KEY_TG_REPORT_OFF_FIELDS] = "location,soc,trip"
        repo.addTgReportOdometerOnce()
        assertEquals(
            setOf(ReportField.LOCATION, ReportField.SOC, ReportField.ODOMETER, ReportField.TRIP),
            repo.getTgReportOffFields(),
        )
        repo.setTgReportOffFields(setOf(ReportField.SOC, ReportField.TRIP))
        repo.addTgReportOdometerOnce() // the next start
        assertEquals(setOf(ReportField.SOC, ReportField.TRIP), repo.getTgReportOffFields())
    }

    @Test fun `a fresh install keeps the default and is never touched later`() = runTest {
        repo.addTgReportOdometerOnce()
        assertNull(dao.map[SettingsRepository.KEY_TG_REPORT_OFF_FIELDS])
        assertEquals(ReportField.DEFAULT, repo.getTgReportOffFields())
        repo.setTgReportOffFields(setOf(ReportField.SOC))
        repo.addTgReportOdometerOnce()
        assertEquals(setOf(ReportField.SOC), repo.getTgReportOffFields())
    }
}
