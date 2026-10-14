package com.bydmate.app.helper

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TX_SPLIT37_ROOT_STATE body: the root and the task are picked out of the framework lists by id and
 * read field by field, so a ROM missing one field still reports the rest. Robolectric for the real
 * ComponentName flatten/unflatten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Split37RootStateReadingTest {

    /** Stand-in for ActivityTaskManager.RootTaskInfo (field names as on API 32). */
    class FakeRootInfo(
        @JvmField val taskId: Int,
        @JvmField val topActivity: ComponentName?,
        @JvmField val visible: Boolean,
        @JvmField val childTaskIds: IntArray,
        @JvmField val childTaskNames: Array<String>,
    )

    /** Stand-in for ActivityManager.RunningTaskInfo. */
    class FakeTask(
        @JvmField val taskId: Int,
        @JvmField val isVisible: Boolean,
        @JvmField val topActivity: ComponentName?,
    )

    /** A ROM whose TaskInfo has neither visibility field nor a top activity. */
    class BareTask(@JvmField val taskId: Int)

    private val navi = ComponentName("ru.yandex.yandexnavi", "ru.yandex.yandexnavi.map.MapActivity")
    private val sr = ComponentName("com.byd.sr", "com.byd.sr.SrActivity")

    private val roots = listOf<Any>(
        FakeRootInfo(2, null, true, intArrayOf(21), arrayOf("pkg.music/pkg.music.Main")),
        FakeRootInfo(3, sr, true, intArrayOf(23, 31), arrayOf(navi.flattenToString(), sr.flattenToString())),
    )

    @Test fun `reads the root's top, children and the task's visibility`() {
        val tasks = listOf(FakeTask(21, true, null), FakeTask(23, false, navi))

        val reading = split37RootStateReading(roots, 3, tasks, 23)

        assertEquals(
            Split37RootStateReading(
                rootTop = "com.byd.sr/.SrActivity",
                rootVisible = 1,
                children = listOf(23 to "ru.yandex.yandexnavi/.map.MapActivity", 31 to "com.byd.sr/.SrActivity"),
                taskVisible = 0,
                taskTop = "ru.yandex.yandexnavi/.map.MapActivity",
            ),
            reading,
        )
    }

    @Test fun `an unlisted root and task answer the unknown markers`() {
        val reading = split37RootStateReading(roots, 99, listOf(FakeTask(21, true, null)), 77)

        assertEquals(Split37RootStateReading("", -1, null, -1, ""), reading)
    }

    @Test fun `a task without the visibility field is unknown, not invisible`() {
        val reading = split37RootStateReading(roots, 3, listOf(null, BareTask(23)), 23)

        assertEquals(-1, reading.taskVisible)
        assertEquals("", reading.taskTop)
        assertEquals("com.byd.sr/.SrActivity", reading.rootTop)
    }

    @Test fun `a silent task list still reports the root`() {
        val reading = split37RootStateReading(roots, 2, null, 21)

        assertEquals(listOf(21 to "pkg.music/.Main"), reading.children)
        assertEquals(-1, reading.taskVisible)
    }
}
