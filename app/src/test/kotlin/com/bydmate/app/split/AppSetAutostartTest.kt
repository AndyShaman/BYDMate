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
        boot: Int? = null,
    ) = AppSetAutostart(
        st.apply { setSetsMode(!splitOn); setSetsOn(!splitOn && switchedOn) }, launcher, fakeSplitSharedPrefs(backing),
        legacyHeadUnit = { legacy }, splitFeatureEnabled = { splitOn && switchedOn },
        startSplit = { splitStarts++; splitResult }, now = { clock }, pause = { pauses += it },
        bootCount = { boot },
    )

    @Test fun `with the main method chosen its saved pair opens, not a set`() = runBlocking {
        assertEquals(Result.STARTED, autostart(splitOn = true).runIfDue())

        assertEquals(1, splitStarts)
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `the main method without a saved pair opens nothing`() = runBlocking {
        splitResult = null

        assertEquals(Result.NO_SET, autostart(splitOn = true).runIfDue())
    }

    @Test fun `the main method that does not start is reported`() = runBlocking {
        splitResult = SplitStartResult.LAUNCH_FAILED

        assertEquals(Result.FAILED, autostart(splitOn = true).runIfDue())
    }

    @Test fun `the switch off opens nothing with either method`() = runBlocking {
        val a = autostart()
        a.setEnabled(false)

        assertEquals(Result.OFF, a.runIfDue())
        assertEquals(Result.OFF, autostart(splitOn = true).runIfDue())
        assertEquals(0, splitStarts)
        coVerify(exactly = 0) { launcher.launch(any()) }
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test fun `the split switched off opens nothing`() = runBlocking {
        assertEquals(Result.OFF, autostart(switchedOn = false).runIfDue())
        assertEquals(0, splitStarts)
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `the switch starts off without a set that had its own open at start, and on when set`() = runBlocking {
        val plain = AppSetStore().apply { upsert(set.copy(autostart = false)) }
        coEvery { launcher.launch(any()) } returns AppSetLauncher.Outcome.OK

        assertEquals(Result.OFF, autostart(st = plain).runIfDue())
        autostart(st = plain).setEnabled(true)
        assertEquals(Result.STARTED, autostart(st = plain).runIfDue())
    }

    @Test fun `a start opens the set once`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK

        assertEquals(Result.STARTED, autostart().runIfDue())

        coVerify(exactly = 1) { launcher.launch(set) }
        assertEquals(listOf(AppSetAutostart.START_DELAY_MS), pauses)
    }

    @Test fun `the restart seconds later does not start it again`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK
        autostart().runIfDue()
        clock += 13_000L

        assertEquals(Result.REPEAT, autostart().runIfDue())

        coVerify(exactly = 1) { launcher.launch(set) }
    }

    @Test fun `the next car start opens it again`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK
        autostart().runIfDue()
        clock += AppSetAutostart.REPEAT_WINDOW_MS + 1

        assertEquals(Result.STARTED, autostart().runIfDue())
    }

    @Test fun `a camera that does not close opens nothing`() = runBlocking {
        coEvery { launcher.cameraClosed() } returns false

        assertEquals(Result.CAMERA, autostart().runIfDue())
        assertEquals(Result.CAMERA, autostart(splitOn = true).runIfDue())
        coVerify(exactly = 0) { launcher.launch(any()) }
        assertEquals(0, splitStarts)
    }

    @Test fun `a failed launch is reported and not retried by the next restart`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.LEFT_FAILED

        assertEquals(Result.FAILED, autostart().runIfDue())
        assertEquals(Result.REPEAT, autostart().runIfDue())
    }

    @Test fun `other firmwares open the saved pair, never a set`() = runBlocking {
        val a = autostart(legacy = false, splitOn = true)
        a.setEnabled(true)

        assertEquals(Result.STARTED, a.runIfDue())
        assertEquals(1, splitStarts)
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `an empty choice does nothing`() = runBlocking {
        val empty = autostart(st = AppSetStore())
        empty.setEnabled(true)
        assertEquals(Result.NO_SET, empty.runIfDue())
        coVerify(exactly = 0) { launcher.launch(any()) }
    }

    @Test fun `a car already moving at the start still gets the split`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK

        // No speed is read at all: the service gets here ~30 s after the head unit booted.
        assertEquals(Result.STARTED, autostart().runIfDue())
        coVerify(exactly = 1) { launcher.launch(set) }
    }

    // --- Once per head-unit boot (2026-11-21 21:35: a car start 90 s after an opening got REPEAT). ---

    @Test fun `the recovery restart in the same boot does not open it again`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK
        autostart(boot = 228).runIfDue()
        clock += 15_000L

        assertEquals(Result.REPEAT, autostart(boot = 228).runIfDue())
        coVerify(exactly = 1) { launcher.launch(set) }
    }

    @Test fun `a new car start opens it again a minute later`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK
        autostart(boot = 227).runIfDue()
        clock += 90_000L

        assertEquals(Result.STARTED, autostart(boot = 228).runIfDue())
        coVerify(exactly = 2) { launcher.launch(set) }
    }

    @Test fun `an update installed after this boot's opening does not open it again`() = runBlocking {
        coEvery { launcher.launch(set) } returns AppSetLauncher.Outcome.OK
        autostart(boot = 228).runIfDue()
        clock += 60 * 60_000L

        assertEquals(Result.REPEAT, autostart(boot = 228).runIfDue())
    }
}
