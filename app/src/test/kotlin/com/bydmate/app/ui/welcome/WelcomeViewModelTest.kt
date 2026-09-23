package com.bydmate.app.ui.welcome

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.AdbConnectFailure
import com.bydmate.app.data.autoservice.AdbOnDeviceClient
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.ui.widget.WidgetController
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WelcomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val tripRepository: TripRepository = mockk(relaxed = true)
    private val historyImporter: HistoryImporter = mockk(relaxed = true)
    private val adbClient: AdbOnDeviceClient = mockk(relaxed = true)
    private val productionRelocale = WidgetController.splitOverlayRelocaleAction
    private lateinit var ctx: Context
    private lateinit var localePreferences: LocalePreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Keep the split overlay out of unit tests.
        WidgetController.splitOverlayRelocaleAction = {}
        ctx = ApplicationProvider.getApplicationContext()
        localePreferences = LocalePreferences(ctx)
        localePreferences.setLanguage("en")
        coEvery { tripRepository.getTripCount() } returns 0
    }

    @After
    fun tearDown() {
        WidgetController.splitOverlayRelocaleAction = productionRelocale
        Dispatchers.resetMain()
    }

    private fun viewModel() =
        WelcomeViewModel(ctx, settingsRepository, localePreferences, tripRepository, historyImporter, adbClient)

    @Test
    fun `steps start at 1, stop at TOTAL_STEPS and never go below 1`() {
        val vm = viewModel()
        assertEquals(1, vm.uiState.value.step)
        vm.prevStep()
        assertEquals(1, vm.uiState.value.step)
        repeat(WelcomeViewModel.TOTAL_STEPS + 2) { vm.nextStep() }
        assertEquals(4, vm.uiState.value.step)
        repeat(WelcomeViewModel.TOTAL_STEPS + 2) { vm.prevStep() }
        assertEquals(1, vm.uiState.value.step)
    }

    @Test
    fun `wizard has four steps and the ADB step sits between tariffs and autostart`() {
        assertEquals(4, WelcomeViewModel.TOTAL_STEPS)
        val vm = viewModel()
        repeat(2) { vm.nextStep() }
        assertEquals(3, vm.uiState.value.step)
        vm.nextStep()
        assertEquals(4, vm.uiState.value.step)
        vm.prevStep()
        assertEquals(3, vm.uiState.value.step)
    }

    @Test
    fun `checkAdb goes Checking then Ok on a successful connect`() = runTest(testDispatcher) {
        coEvery { adbClient.connect() } returns Result.success(Unit)
        val vm = viewModel()
        assertEquals(AdbCheck.NotChecked, vm.uiState.value.adbCheck)

        vm.checkAdb()
        assertEquals(AdbCheck.Checking, vm.uiState.value.adbCheck)
        advanceUntilIdle()
        assertEquals(AdbCheck.Ok, vm.uiState.value.adbCheck)
    }

    @Test
    fun `checkAdb goes Checking then Failed on a refused connect`() = runTest(testDispatcher) {
        coEvery { adbClient.connect() } returns Result.failure(java.io.IOException("ADB connect refused"))
        every { adbClient.lastConnectFailure() } returns AdbConnectFailure.UNREACHABLE
        val vm = viewModel()

        vm.checkAdb()
        assertEquals(AdbCheck.Checking, vm.uiState.value.adbCheck)
        advanceUntilIdle()
        assertEquals(AdbCheck.Failed, vm.uiState.value.adbCheck)
    }

    @Test
    fun `checkAdb is ignored while a check is running`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Result<Unit>>()
        coEvery { adbClient.connect() } coAnswers { gate.await() }
        val vm = viewModel()

        vm.checkAdb()
        advanceUntilIdle()
        vm.checkAdb()
        advanceUntilIdle()
        gate.complete(Result.success(Unit))
        advanceUntilIdle()

        coVerify(exactly = 1) { adbClient.connect() }
        assertEquals(AdbCheck.Ok, vm.uiState.value.adbCheck)
    }

    @Test
    fun `initial language comes from LocalePreferences`() {
        assertEquals("en", viewModel().uiState.value.language)
    }

    @Test
    fun `setLanguage persists app_language and updates state`() {
        val vm = viewModel()
        vm.setLanguage("pl")
        assertEquals("pl", localePreferences.getLanguage())
        assertEquals(
            "pl",
            ctx.getSharedPreferences(LocalePreferences.FILE, Context.MODE_PRIVATE)
                .getString(LocalePreferences.KEY_LANG, null)
        )
        assertEquals("pl", vm.uiState.value.language)
    }

    @Test
    fun `choosing Chinese switches wizard currency to CNY`() {
        val vm = viewModel()
        vm.setLanguage("zh")
        assertEquals("CNY", vm.uiState.value.currency)
    }

    @Test
    fun `switching from Chinese back to English restores the default currency`() {
        val vm = viewModel()
        vm.setLanguage("zh")
        vm.setLanguage("en")
        assertEquals(SettingsRepository.DEFAULT_CURRENCY, vm.uiState.value.currency)
    }

    @Test
    fun `currency picked by the user is not overridden by a language change`() {
        val vm = viewModel()
        vm.setCurrency("USD")
        vm.setLanguage("zh")
        assertEquals("USD", vm.uiState.value.currency)
        assertEquals("$", vm.uiState.value.currencySymbol)
    }

    @Test
    fun `initial currency follows the stored language`() {
        localePreferences.setLanguage("zh")
        val state = viewModel().uiState.value
        assertEquals("zh", state.language)
        assertEquals("CNY", state.currency)
        assertEquals("¥", state.currencySymbol)
    }

    @Test
    fun `startBydMate saves capacity, currency and tariffs but not the trip cost tariff`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.setBatteryCapacity("31.8")
        vm.setCurrency("RUB")
        vm.setHomeTariff("5.5")
        vm.setDcTariff("20")

        vm.startBydMate()
        advanceUntilIdle()

        coVerify { settingsRepository.setString(SettingsRepository.KEY_BATTERY_CAPACITY, "31.8") }
        coVerify { settingsRepository.setString(SettingsRepository.KEY_CURRENCY, "RUB") }
        coVerify { settingsRepository.setString(SettingsRepository.KEY_HOME_TARIFF, "5.5") }
        coVerify { settingsRepository.setString(SettingsRepository.KEY_DC_TARIFF, "20") }
        coVerify(exactly = 0) { settingsRepository.setString(SettingsRepository.KEY_TRIP_COST_TARIFF, any()) }
        assertTrue(vm.uiState.value.isComplete)
    }
}
