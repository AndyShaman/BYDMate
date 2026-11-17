package com.bydmate.app.split

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SplitTaskState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSetLauncherTest {

    private val set = AppSet("set1", "Комплект", "com.zhiliaoapp.musically", "ru.yandex.music")
    private val pauses = mutableListOf<Long>()
    private val helper = mockk<HelperClient>(relaxed = true)
    private val launcher = AppSetLauncher(helper) { pauses += it }

    private fun task(id: Int) = SplitTaskState(taskId = id, windowingMode = 1, displayId = 0, left = 0, top = 0, right = 1920, bottom = 1080)

    @Test fun `the right app opens first and the left one goes into the split after it`() = runBlocking {
        coEvery { helper.launchApp(any()) } returns true
        coEvery { helper.getTaskState("ru.yandex.music") } returns task(7)
        coEvery { helper.launchAppInWindowingMode(any(), any()) } returns true

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set))

        coVerifyOrder {
            helper.launchApp("ru.yandex.music")
            helper.getTaskState("ru.yandex.music")
            helper.launchAppInWindowingMode("com.zhiliaoapp.musically", AppSetLauncher.WINDOWING_SPLIT_PRIMARY)
        }
        assertEquals(listOf(AppSetLauncher.SETTLE_MS), pauses)
    }

    @Test fun `a set without a right app only launches the left one`() = runBlocking {
        coEvery { helper.launchAppInWindowingMode(any(), any()) } returns true

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set.copy(rightPkg = null)))

        coVerify(exactly = 0) { helper.launchApp(any()) }
        coVerify(exactly = 1) { helper.launchAppInWindowingMode("com.zhiliaoapp.musically", 3) }
        assertTrue(pauses.isEmpty())
    }

    @Test fun `a right app that does not start stops the set before the split`() = runBlocking {
        coEvery { helper.launchApp(any()) } returns false

        assertEquals(AppSetLauncher.Outcome.RIGHT_FAILED, launcher.launch(set))

        coVerify(exactly = 0) { helper.launchAppInWindowingMode(any(), any()) }
    }

    @Test fun `a left app that does not start is reported`() = runBlocking {
        coEvery { helper.launchApp(any()) } returns true
        coEvery { helper.getTaskState(any()) } returns task(7)
        coEvery { helper.launchAppInWindowingMode(any(), any()) } returns false

        assertEquals(AppSetLauncher.Outcome.LEFT_FAILED, launcher.launch(set))
    }

    @Test fun `a right app that never shows up is waited for a bounded time, then the split goes on`() = runBlocking {
        coEvery { helper.launchApp(any()) } returns true
        coEvery { helper.getTaskState(any()) } returns null
        coEvery { helper.launchAppInWindowingMode(any(), any()) } returns true

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set))

        assertEquals(AppSetLauncher.TASK_WAIT_MS + AppSetLauncher.SETTLE_MS, pauses.sum())
    }

    @Test fun `a daemon that is down while waiting does not break the launch`() = runBlocking {
        coEvery { helper.launchApp(any()) } returns true
        coEvery { helper.getTaskState(any()) } throws IllegalStateException("channel closed")
        coEvery { helper.launchAppInWindowingMode(any(), any()) } returns true

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set))
    }
}
