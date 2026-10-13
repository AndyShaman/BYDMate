package com.bydmate.app.data.weather

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class StockWeatherManagerTest {
    private lateinit var prefs: StockWeatherPreferences
    private lateinit var client: StockWeatherClient
    private lateinit var manager: StockWeatherManager
    private lateinit var locations: MutableStateFlow<Location?>
    private val edges = MutableStateFlow(0L)

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(StockWeatherPreferences.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        prefs = StockWeatherPreferences(context)
        client = mockk()
        every { client.hasNetwork() } returns true
        every { client.hasLocationPermission() } returns true
        coEvery { client.refresh(any(), any()) } returns true
        manager = StockWeatherManager(prefs, client)
        locations = MutableStateFlow(fix())
    }

    @Test fun `default off does not fetch weather even after a refresh click`() = runTest {
        manager.start(backgroundScope, locations, edges)
        manager.requestRefresh()
        manager.onRefreshClick("com.byd.weatherdata", "com.byd.weatherdata:id/iv_refresh")
        runCurrent()
        assertFalse(prefs.enabled.value)
        assertEquals(StockWeatherState.Disabled, manager.state.value)
        coVerify(exactly = 0) { client.refresh(any(), any()) }
    }

    @Test fun `enabling updates once and duplicate GPS events do not flood requests`() = runTest {
        manager.start(backgroundScope, locations, edges)
        prefs.setEnabled(true)
        runCurrent()
        assertEquals(StockWeatherState.Updated, manager.state.value)
        repeat(5) { locations.value = fix(); runCurrent() }
        coVerify(exactly = 1) { client.refresh(any(), any()) }
        assertTrue(prefs.lastSuccessMs.value > 0)
    }

    @Test fun `stale location waits for a fresh GPS fix`() = runTest {
        locations.value = fix().apply { time -= 31 * 60 * 1000L }
        prefs.setEnabled(true)
        manager.start(backgroundScope, locations, edges)
        runCurrent()
        assertEquals(StockWeatherState.WaitingLocation, manager.state.value)
        coVerify(exactly = 0) { client.refresh(any(), any()) }
        locations.value = fix()
        runCurrent()
        assertEquals(StockWeatherState.Updated, manager.state.value)
    }

    @Test fun `missing network waits until the shared network monitor reports recovery`() = runTest {
        every { client.hasNetwork() } returns false
        prefs.setEnabled(true)
        manager.start(backgroundScope, locations, edges)
        runCurrent()
        assertEquals(StockWeatherState.WaitingInternet, manager.state.value)
        every { client.hasNetwork() } returns true
        edges.value = 1
        runCurrent()
        assertEquals(StockWeatherState.Updated, manager.state.value)
    }

    @Test fun `configured interval schedules the next update`() = runTest {
        prefs.setIntervalMinutes(5)
        prefs.setEnabled(true)
        manager.start(backgroundScope, locations, edges)
        runCurrent()
        advanceTimeBy(5 * 60 * 1000L - 1)
        runCurrent()
        coVerify(exactly = 1) { client.refresh(any(), any()) }
        advanceTimeBy(1)
        runCurrent()
        coVerify(exactly = 2) { client.refresh(any(), any()) }
    }

    @Test fun `disabling cancels the active update and future updates`() = runTest {
        val pending = CompletableDeferred<Boolean>()
        coEvery { client.refresh(any(), any()) } coAnswers { pending.await() }
        prefs.setEnabled(true)
        manager.start(backgroundScope, locations, edges)
        runCurrent()
        assertEquals(StockWeatherState.Updating, manager.state.value)
        prefs.setEnabled(false)
        runCurrent()
        pending.complete(true)
        advanceTimeBy(30 * 60 * 1000L)
        runCurrent()
        assertEquals(StockWeatherState.Disabled, manager.state.value)
        assertEquals(0L, prefs.lastSuccessMs.value)
        coVerify(exactly = 1) { client.refresh(any(), any()) }
    }

    @Test fun `only the stock refresh button forces an update before the interval`() = runTest {
        prefs.setEnabled(true)
        manager.start(backgroundScope, locations, edges)
        runCurrent()
        manager.onRefreshClick("other.app", "com.byd.weatherdata:id/iv_refresh")
        manager.onRefreshClick("com.byd.weatherdata", "com.byd.weatherdata:id/other")
        runCurrent()
        coVerify(exactly = 1) { client.refresh(any(), any()) }
        manager.onRefreshClick("com.byd.weatherdata", "com.byd.weatherdata:id/iv_refresh")
        runCurrent()
        coVerify(exactly = 2) { client.refresh(any(), any()) }
    }

    @Test fun `restart honors the persisted successful update time`() = runTest {
        prefs.recordSuccess(System.currentTimeMillis())
        prefs.setEnabled(true)
        manager.start(backgroundScope, locations, edges)
        runCurrent()
        coVerify(exactly = 0) { client.refresh(any(), any()) }
        manager.requestRefresh()
        runCurrent()
        assertEquals(StockWeatherState.Updated, manager.state.value)
    }

    @Test fun `a timed out request is reported and retried without killing the updater`() = runTest {
        val pending = CompletableDeferred<Boolean>()
        coEvery { client.refresh(any(), any()) } coAnswers { pending.await() }
        prefs.setEnabled(true)
        manager.start(backgroundScope, locations, edges)
        runCurrent()
        advanceTimeBy(45_000L)
        runCurrent()
        assertEquals(StockWeatherState.Failed, manager.state.value)
        coEvery { client.refresh(any(), any()) } returns true
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertEquals(StockWeatherState.Updated, manager.state.value)
        coVerify(exactly = 2) { client.refresh(any(), any()) }
    }

    @Test fun `missing permission blocks every update`() = runTest {
        every { client.hasLocationPermission() } returns false
        prefs.setEnabled(true)
        manager.start(backgroundScope, locations, edges)
        runCurrent()
        assertEquals(StockWeatherState.NoLocationPermission, manager.state.value)
        coVerify(exactly = 0) { client.refresh(any(), any()) }
    }

    @Test fun `unsupported weather provider degrades independently`() = runTest {
        coEvery { client.refresh(any(), any()) } throws StockWeatherClient.Unavailable()
        prefs.setEnabled(true)
        manager.start(backgroundScope, locations, edges)
        runCurrent()
        assertEquals(StockWeatherState.Unavailable, manager.state.value)
        assertEquals(0L, prefs.lastSuccessMs.value)
    }

    private fun fix() = Location("gps").apply {
        latitude = 53.9; longitude = 27.56; time = System.currentTimeMillis()
    }
}
