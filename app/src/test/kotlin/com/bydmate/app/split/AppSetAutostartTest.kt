package com.bydmate.app.split

import com.bydmate.app.split.AppSetAutostart.Result
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSetAutostartTest {

    private val set = AppSet("set1", "Видео", "com.zhiliaoapp.musically", "ru.yandex.music", autostart = true)
    private val store = AppSetStore().apply { upsert(set) }
    private val launcher = mockk<AppSetLauncher>().also { coEvery { it.cameraClosed() } returns true }
    private val backing = mutableMapOf<String, Any?>()
    private val pauses = mutableListOf<Long>()
    private var clock = 1_000_000_000L

    private var splitStarts = 0
    private var splitResult: SplitStartResult? = SplitStartResult.OK

    /** [splitOn]: the saved pair's mechanism (not «Пополам 1:1»); [switchedOn]: the split at all. */
    private fun autostart(
        legacy: Boolean = true, st: AppSetStore = store, splitOn: Boolean = false, switchedOn: Boolean = true,
    ) = AppSetAutostart(
        st.apply { setSetsMode(!splitOn); setSetsOn(!splitOn && switchedOn) }, launcher, fakeSplitSharedPrefs(backing),
        legacyHeadUnit = { legacy }, splitFeatureEnabled = { splitOn && switchedOn },
        startSplit = { splitStarts++; splitResult }, now = { clock }, pause = { pauses += it },
    )

    @Test fun `with the main method chosen its saved pair opens, not a set`() = runBlocking {
        assertEquals(Result.STARTED, autostart(splitOn = true).runIfDue { 0 })

        assertEquals(1, splitStarts)
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `the main method without a saved pair opens nothing`() = runBlocking {
        splitResult = null

        assertEquals(Result.NO_SET, autostart(splitOn = true).runIfDue { 0 })
    }

    @Test fun `the main method that does not start is reported`() = runBlocking {
        splitResult = SplitStartResult.LAUNCH_FAILED

        assertEquals(Result.FAILED, autostart(splitOn = true).runIfDue { 0 })
    }

    @Test fun `the switch off opens nothing with either method`() = runBlocking {
        val a = autostart()
        a.setEnabled(false)

        assertEquals(Result.OFF, a.runIfDue { 0 })
        assertEquals(Result.OFF, autostart(splitOn = true).runIfDue { 0 })
        assertEquals(0, splitStarts)
        coVerify(exactly = 0) { launcher.launch(any()) }
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test fun `the split switched off opens nothing`() = runBlocking {
        assertEquals(Result.OFF, autostart(switchedOn = false).runIfDue { 0 })
        assertEquals(0, splitStarts)
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `the switch starts off without a set that had its own open at start, and on when set`() = runBlocking {
        val plain = AppSetStore().apply { upsert(set.copy(autostart = false)) }
        coEvery { launcher.launch(any()) } returns AppSetLauncher.Outcome.OK

        assertEquals(Result.OFF, autostart(st = plain).runIfDue { 0 })
        autostart(st = plain).setEnabled(true)
        assertEquals(Result.STARTED, autostart(st = plain).runIfDue { 0 })
    }

    @Test fun `a standing car starts the set once`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK

        assertEquals(Result.STARTED, autostart().runIfDue { 0 })

        coVerify(exactly = 1) { launcher.launch(set) }
        assertEquals(listOf(AppSetAutostart.START_DELAY_MS), pauses)
    }

    @Test fun `the restart seconds later does not start it again`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK
        autostart().runIfDue { 0 }
        clock += 13_000L

        assertEquals(Result.REPEAT, autostart().runIfDue { 0 })

        coVerify(exactly = 1) { launcher.launch(set) }
    }

    @Test fun `the next car start opens it again`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK
        autostart().runIfDue { 0 }
        clock += AppSetAutostart.REPEAT_WINDOW_MS + 1

        assertEquals(Result.STARTED, autostart().runIfDue { 0 })
    }

    @Test fun `a camera that does not close opens nothing`() = runBlocking {
        coEvery { launcher.cameraClosed() } returns false

        assertEquals(Result.CAMERA, autostart().runIfDue { 0 })
        assertEquals(Result.CAMERA, autostart(splitOn = true).runIfDue { 0 })
        coVerify(exactly = 0) { launcher.launch(any()) }
        assertEquals(0, splitStarts)
    }

    @Test fun `the speed is read once the camera has closed`() = runBlocking {
        var cameraGone = false
        coEvery { launcher.cameraClosed() } coAnswers { cameraGone = true; true }
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK

        assertEquals(Result.STARTED, autostart().runIfDue { if (cameraGone) 0 else 12 })
    }

    @Test fun `a moving car never gets a video app opened`() = runBlocking {
        assertEquals(Result.MOVING, autostart().runIfDue { 12 })
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `no speed reading means no launch, after a bounded wait`() = runBlocking {
        assertEquals(Result.NO_SPEED, autostart().runIfDue { null })

        assertEquals(AppSetAutostart.START_DELAY_MS + AppSetAutostart.SPEED_WAIT_MS, pauses.sum())
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `a speed that shows up during the wait is used`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK
        var reads = 0

        assertEquals(Result.STARTED, autostart().runIfDue { if (++reads < 4) null else 0 })
    }

    @Test fun `a failed launch is reported and not retried by the next restart`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.LEFT_FAILED

        assertEquals(Result.FAILED, autostart().runIfDue { 0 })
        assertEquals(Result.REPEAT, autostart().runIfDue { 0 })
    }

    @Test fun `other firmwares open the saved pair, never a set`() = runBlocking {
        val a = autostart(legacy = false, splitOn = true)
        a.setEnabled(true)

        assertEquals(Result.STARTED, a.runIfDue { 0 })
        assertEquals(1, splitStarts)
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `an empty choice does nothing`() = runBlocking {
        val empty = autostart(st = AppSetStore())
        empty.setEnabled(true)
        assertEquals(Result.NO_SET, empty.runIfDue { 0 })
        coVerify(exactly = 0) { launcher.launch(any()) }
    }
}
