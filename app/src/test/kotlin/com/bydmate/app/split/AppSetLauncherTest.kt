package com.bydmate.app.split

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SplitTaskState
import com.bydmate.app.data.vehicle.TopTaskInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AppSetLauncherTest {

    private val left = "com.zhiliaoapp.musically"
    private val right = "com.waze"
    private val set = AppSet("set1", "Комплект", left, right)
    private val pauses = mutableListOf<Long>()
    private val helper = mockk<HelperClient>(relaxed = true)
    private val launcher = AppSetLauncher(helper) { pauses += it }

    private fun task(id: Int, mode: Int = 1) =
        SplitTaskState(taskId = id, windowingMode = mode, displayId = 0, left = 0, top = 0, right = 1920, bottom = 1080)

    private fun top(pkg: String) = TopTaskInfo(pkg = pkg, taskId = 7, windowingMode = 1, activityType = 1, displayId = 0)

    /** The right app comes up on top, the left one is not running before and ends up in the split. */
    private fun happyPath() {
        coEvery { helper.launchApp(any()) } returns true
        coEvery { helper.getTopTask() } returns top(right)
        coEvery { helper.getTaskState(left) } returnsMany listOf(null, task(9, mode = 3))
        coEvery { helper.launchAppInWindowingMode(any(), any()) } returns true
    }

    @Test fun `the right app opens first and the left one goes into the split after it`() = runBlocking {
        happyPath()

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set))

        coVerifyOrder {
            helper.launchApp(right)
            helper.getTopTask()
            helper.launchAppInWindowingMode(left, AppSetLauncher.WINDOWING_SPLIT_PRIMARY)
            helper.getTaskState(left)
        }
        coVerify(exactly = 1) { helper.launchAppInWindowingMode(any(), any()) }
        assertEquals(listOf(AppSetLauncher.SETTLE_MS, AppSetLauncher.VERIFY_MS), pauses)
    }

    @Test fun `the left app waits until the right one is the top task`() = runBlocking {
        happyPath()
        coEvery { helper.getTopTask() } returnsMany listOf(top("com.android.launcher3"), top("com.android.launcher3"), top(right))

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set))

        assertEquals(
            listOf(AppSetLauncher.TASK_POLL_MS, AppSetLauncher.TASK_POLL_MS, AppSetLauncher.SETTLE_MS, AppSetLauncher.VERIFY_MS),
            pauses,
        )
    }

    @Test fun `without a readable top task the right app's task is enough, as before`() = runBlocking {
        happyPath()
        coEvery { helper.getTopTask() } returns null
        coEvery { helper.getTaskState(right) } returns task(7)

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set))
        assertEquals(listOf(AppSetLauncher.SETTLE_MS, AppSetLauncher.VERIFY_MS), pauses)
    }

    @Test fun `a right app that never shows up is waited for a bounded time, then the split goes on`() = runBlocking {
        happyPath()
        coEvery { helper.getTopTask() } returns top("com.android.launcher3")

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set))

        assertEquals(AppSetLauncher.TASK_WAIT_MS + AppSetLauncher.SETTLE_MS + AppSetLauncher.VERIFY_MS, pauses.sum())
    }

    @Test fun `a left app that stayed fullscreen is moved again and then counts as split`() = runBlocking {
        happyPath()
        // Running before (fullscreen, left there by Home), still fullscreen after the first launch.
        coEvery { helper.getTaskState(left) } returnsMany listOf(task(9), task(9), task(9, mode = 3))

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set))

        coVerify(exactly = 2) { helper.launchAppInWindowingMode(left, AppSetLauncher.WINDOWING_SPLIT_PRIMARY) }
    }

    @Test fun `a left app that is still not split after the retry is reported, not taken for success`() = runBlocking {
        happyPath()
        coEvery { helper.getTaskState(left) } returns task(9)

        assertEquals(AppSetLauncher.Outcome.NOT_SPLIT, launcher.launch(set))

        coVerify(exactly = 2) { helper.launchAppInWindowingMode(left, AppSetLauncher.WINDOWING_SPLIT_PRIMARY) }
    }

    @Test fun `an unreadable windowing mode is not held against the launch`() = runBlocking {
        happyPath()
        coEvery { helper.getTaskState(left) } returns null

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set))
        coVerify(exactly = 1) { helper.launchAppInWindowingMode(any(), any()) }
    }

    @Test fun `a set without a right app only launches the left one`() = runBlocking {
        happyPath()

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set.copy(rightPkg = null)))

        coVerify(exactly = 0) { helper.launchApp(any()) }
        coVerify(exactly = 1) { helper.launchAppInWindowingMode(left, 3) }
        assertEquals(listOf(AppSetLauncher.VERIFY_MS), pauses)
    }

    @Test fun `a right app that does not start stops the set before the split`() = runBlocking {
        coEvery { helper.launchApp(any()) } returns false

        assertEquals(AppSetLauncher.Outcome.RIGHT_FAILED, launcher.launch(set))

        coVerify(exactly = 0) { helper.launchAppInWindowingMode(any(), any()) }
    }

    @Test fun `a left app that does not start is reported`() = runBlocking {
        happyPath()
        coEvery { helper.launchAppInWindowingMode(any(), any()) } returns false

        assertEquals(AppSetLauncher.Outcome.LEFT_FAILED, launcher.launch(set))
        // Only the wait for the right app: no read-back of a launch that failed.
        assertEquals(listOf(AppSetLauncher.SETTLE_MS), pauses)
    }

    @Test fun `a daemon that is down while waiting does not break the launch`() = runBlocking {
        coEvery { helper.launchApp(any()) } returns true
        coEvery { helper.getTopTask() } throws IllegalStateException("channel closed")
        coEvery { helper.getTaskState(any()) } throws IllegalStateException("channel closed")
        coEvery { helper.launchAppInWindowingMode(any(), any()) } returns true

        assertEquals(AppSetLauncher.Outcome.OK, launcher.launch(set))
    }
}
