package com.bydmate.app.helper

import android.content.ComponentName
import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Fake tasks: plain classes carrying exactly the field/method names the helper looks up by reflection.
open class FakeBase(val topActivity: ComponentName?, val baseActivity: ComponentName? = null)

class FakeTask(
    top: ComponentName?, base: ComponentName? = null,
    val taskId: Int, val displayId: Int = 0, val isVisible: Boolean = true, val configuration: Any? = null,
) : FakeBase(top, base)

/** No isVisible field. */
class FakeBareTask(
    top: ComponentName?, val taskId: Int, val displayId: Int = 0, val configuration: Any? = null,
) : FakeBase(top)

/** Only the fallback `id` field. */
class FakeIdOnlyTask(top: ComponentName?, val id: Int, val displayId: Int = 0, val configuration: Any? = null) : FakeBase(top)

class FakeNoIdTask(top: ComponentName?, val displayId: Int = 0, val configuration: Any? = null) : FakeBase(top)

class FakeNoDisplayTask(top: ComponentName?, val taskId: Int, val configuration: Any? = null) : FakeBase(top)

/** displayId of the wrong type: Field.getInt throws. */
class FakeBadDisplayTask(top: ComponentName?, val taskId: Int, val displayId: String = "x", val configuration: Any? = null) : FakeBase(top)

class FakeNoConfigTask(top: ComponentName?, val taskId: Int, val displayId: Int = 0) : FakeBase(top)

class FakeConfig(val windowConfiguration: Any?)
class FakeConfigNoWin { val other = 1 }

class FakeWin(private val mode: Int, private val type: Int, private val rect: Rect) {
    fun getWindowingMode() = mode
    fun getActivityType() = type
    fun getBounds() = rect
}

class FakeWinMBounds(private val mode: Int, private val type: Int, @JvmField val mBounds: Rect) {
    fun getWindowingMode() = mode
    fun getActivityType() = type
}

class FakeWinNoBounds(private val mode: Int, private val type: Int) {
    fun getWindowingMode() = mode
    fun getActivityType() = type
}

class FakeWinModeThrows(private val type: Int = 1) {
    fun getWindowingMode(): Int = error("mode")
    fun getActivityType() = type
}

class FakeWinTypeThrows(private val mode: Int = 5) {
    fun getWindowingMode() = mode
    fun getActivityType(): Int = error("type")
}

private class Src(val list: List<*>?) {
    var asked = -1
    fun call(n: Int): List<*>? { asked = n; return list }
}

private fun cn(pkg: String) = ComponentName(pkg, "$pkg.Main")
private fun cfg(win: Any?) = FakeConfig(win)
private val RECT = Rect(1, 2, 3, 4)
private fun win() = cfg(FakeWin(5, 1, RECT))

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperDaemonTaskWalkTest {

    // ---- sizes, null list, empty list, null element ----

    @Test
    fun `each walk asks the source for its own size`() {
        Src(null).also { findTaskId("p", it::call); assertEquals(100, it.asked) }
        Src(null).also { packageForTask(1, it::call); assertEquals(100, it.asked) }
        Src(null).also { topTaskPackage(it::call); assertEquals(1, it.asked) }
        Src(null).also { topTaskInfo(it::call); assertEquals(10, it.asked) }
        Src(null).also { findTaskState("p", it::call); assertEquals(100, it.asked) }
        Src(null).also { taskModeState(1, it::call); assertEquals(100, it.asked) }
        Src(null).also { taskActivityType(1, it::call); assertEquals(100, it.asked) }
    }

    @Test
    fun `null list and empty list give the not-found answer of every walk`() {
        for (src in listOf<List<*>?>(null, emptyList<Any>())) {
            val f = { _: Int -> src }
            assertEquals(-1, findTaskId("p", f))
            assertNull(packageForTask(1, f))
            assertNull(topTaskPackage(f))
            assertNull(topTaskInfo(f))
            assertNull(findTaskState("p", f))
            assertNull(taskModeState(1, f))
            assertEquals(-1, taskActivityType(1, f))
        }
    }

    @Test
    fun `a null element is skipped by the walks that iterate`() {
        val tasks = listOf(null, FakeTask(cn("p"), taskId = 7, configuration = win()))
        val f = { _: Int -> tasks }
        assertEquals(7, findTaskId("p", f))
        assertEquals("p", packageForTask(7, f))
        assertEquals(7, topTaskInfo(f)?.taskId)
        assertEquals(7, findTaskState("p", f)?.taskId)
        assertEquals(5, taskModeState(7, f)?.windowingMode)
        assertEquals(1, taskActivityType(7, f))
    }

    // ---- findTaskId ----

    @Test
    fun `findTaskId matches topActivity then baseActivity and reads taskId or id`() {
        assertEquals(3, findTaskId("a") { listOf(FakeTask(cn("a"), taskId = 3)) })
        assertEquals(4, findTaskId("b") { listOf(FakeTask(null, cn("b"), taskId = 4)) })
        assertEquals(9, findTaskId("a") { listOf(FakeIdOnlyTask(cn("a"), 9)) })
    }

    @Test
    fun `findTaskId skips a matching task without an id field and uses a later one`() {
        val tasks = listOf(FakeNoIdTask(cn("a")), FakeTask(cn("a"), taskId = 8))
        assertEquals(8, findTaskId("a") { tasks })
        assertEquals(-1, findTaskId("a") { listOf(FakeNoIdTask(cn("a"))) })
    }

    @Test
    fun `findTaskId returns -1 without a match`() {
        assertEquals(-1, findTaskId("zzz") { listOf(FakeTask(cn("a"), taskId = 3)) })
    }

    @Test
    fun `findTaskId lets a throwing source reach the caller`() {
        try {
            findTaskId("a") { throw IllegalStateException("boom") }
            fail("expected exception")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    // ---- packageForTask ----

    @Test
    fun `packageForTask returns top else base package of the task with that id`() {
        val tasks = listOf(
            FakeTask(cn("a"), cn("ab"), taskId = 1),
            FakeTask(null, cn("b"), taskId = 2),
            FakeIdOnlyTask(cn("c"), 3),
        )
        assertEquals("a", packageForTask(1) { tasks })
        assertEquals("b", packageForTask(2) { tasks })
        assertEquals("c", packageForTask(3) { tasks })
        assertNull(packageForTask(99) { tasks })
    }

    @Test
    fun `packageForTask is null when the source throws`() {
        assertNull(packageForTask(1) { throw IllegalStateException("boom") })
    }

    // ---- topTaskPackage ----

    @Test
    fun `topTaskPackage reads only the first task`() {
        val tasks = listOf(FakeTask(null, cn("b"), taskId = 1), FakeTask(cn("a"), taskId = 2))
        assertEquals("b", topTaskPackage { tasks })
        assertNull(topTaskPackage { listOf(FakeTask(null, null, taskId = 1), FakeTask(cn("a"), taskId = 2)) })
    }

    @Test
    fun `topTaskPackage does not skip a null first element`() {
        assertNull(topTaskPackage { listOf(null, FakeTask(cn("a"), taskId = 2)) })
    }

    @Test
    fun `topTaskPackage is null when the source throws`() {
        assertNull(topTaskPackage { throw IllegalStateException("boom") })
    }

    // ---- topTaskInfo ----

    @Test
    fun `topTaskInfo returns all six fields of a normal task`() {
        val t = FakeTask(cn("a"), taskId = 11, displayId = 0, isVisible = true, configuration = cfg(FakeWin(5, 2, RECT)))
        assertEquals(TopTaskInfo("a", 11, 5, 2, 0, 1), topTaskInfo { listOf(t) })
    }

    @Test
    fun `topTaskInfo picks the first task on display 0 and skips others`() {
        val tasks = listOf(
            FakeTask(cn("cluster"), taskId = 1, displayId = 2, configuration = win()),
            FakeNoDisplayTask(cn("nodisp"), 2, win()),
            FakeBadDisplayTask(cn("bad"), 3, configuration = win()),
            FakeTask(cn("main"), taskId = 4, displayId = 0, configuration = win()),
        )
        assertEquals("main", topTaskInfo { tasks }?.pkg)
        assertNull(topTaskInfo { tasks.take(3) })
    }

    @Test
    fun `topTaskInfo is null without a package`() {
        assertNull(topTaskInfo { listOf(FakeTask(null, null, taskId = 1, configuration = win())) })
    }

    @Test
    fun `topTaskInfo reports taskId -1 without an id field`() {
        assertEquals(-1, topTaskInfo { listOf(FakeNoIdTask(cn("a"), 0, win())) }?.taskId)
    }

    @Test
    fun `topTaskInfo is null when the window configuration chain is incomplete`() {
        assertNull(topTaskInfo { listOf(FakeNoConfigTask(cn("a"), 1)) })
        assertNull(topTaskInfo { listOf(FakeBareTask(cn("a"), 1, 0, null)) })
        assertNull(topTaskInfo { listOf(FakeBareTask(cn("a"), 1, 0, FakeConfigNoWin())) })
        assertNull(topTaskInfo { listOf(FakeBareTask(cn("a"), 1, 0, cfg(null))) })
    }

    @Test
    fun `topTaskInfo is null when getWindowingMode throws and activityType -1 when getActivityType throws`() {
        assertNull(topTaskInfo { listOf(FakeBareTask(cn("a"), 1, 0, cfg(FakeWinModeThrows()))) })
        val info = topTaskInfo { listOf(FakeBareTask(cn("a"), 1, 0, cfg(FakeWinTypeThrows()))) }
        assertEquals(-1, info?.activityType)
        assertEquals(5, info?.windowingMode)
    }

    @Test
    fun `topTaskInfo visible is 1, 0 or -1 for true, false and absent field`() {
        assertEquals(1, topTaskInfo { listOf(FakeTask(cn("a"), taskId = 1, isVisible = true, configuration = win())) }?.visible)
        assertEquals(0, topTaskInfo { listOf(FakeTask(cn("a"), taskId = 1, isVisible = false, configuration = win())) }?.visible)
        assertEquals(-1, topTaskInfo { listOf(FakeBareTask(cn("a"), 1, 0, win())) }?.visible)
    }

    // ---- findTaskState ----

    @Test
    fun `findTaskState returns all seven fields with bounds from getBounds`() {
        val t = FakeTask(cn("a"), taskId = 21, displayId = 2, configuration = cfg(FakeWin(5, 1, RECT)))
        assertEquals(TaskWindowState(21, 5, 1, 2, 3, 4, 2), findTaskState("a") { listOf(t) })
    }

    @Test
    fun `findTaskState falls back to mBounds, then to zeros`() {
        val m = FakeBareTask(cn("a"), 1, 0, cfg(FakeWinMBounds(5, 1, RECT)))
        assertEquals(TaskWindowState(1, 5, 1, 2, 3, 4, 0), findTaskState("a") { listOf(m) })
        val n = FakeBareTask(cn("a"), 1, 0, cfg(FakeWinNoBounds(5, 1)))
        assertEquals(TaskWindowState(1, 5, 0, 0, 0, 0, 0), findTaskState("a") { listOf(n) })
    }

    @Test
    fun `findTaskState displayId is 0 when the field is absent or of the wrong type`() {
        val absent = FakeNoDisplayTask(cn("a"), 1, win())
        assertEquals(TaskWindowState(1, 5, 1, 2, 3, 4, 0), findTaskState("a") { listOf(absent) })
        val bad = FakeBadDisplayTask(cn("a"), 1, configuration = win())
        assertEquals(TaskWindowState(1, 5, 1, 2, 3, 4, 0), findTaskState("a") { listOf(bad) })
    }

    @Test
    fun `findTaskState is null for a broken chain, no match, or a throwing mode`() {
        assertNull(findTaskState("a") { listOf(FakeNoConfigTask(cn("a"), 1)) })
        assertNull(findTaskState("a") { listOf(FakeBareTask(cn("a"), 1, 0, cfg(null))) })
        assertNull(findTaskState("a") { listOf(FakeBareTask(cn("a"), 1, 0, FakeConfigNoWin())) })
        assertNull(findTaskState("zzz") { listOf(FakeTask(cn("a"), taskId = 1, configuration = win())) })
    }

    @Test
    fun `findTaskState skips a matching task without an id field`() {
        val tasks = listOf(FakeNoIdTask(cn("a"), 0, win()), FakeTask(cn("a"), taskId = 6, configuration = win()))
        assertEquals(6, findTaskState("a") { tasks }?.taskId)
        assertNull(findTaskState("a") { listOf(FakeNoIdTask(cn("a"), 0, win())) })
    }

    // ---- taskModeState ----

    @Test
    fun `taskModeState returns mode and display of the task with that id`() {
        val tasks = listOf(
            FakeTask(cn("a"), taskId = 1, displayId = 0, configuration = cfg(FakeWin(1, 1, RECT))),
            FakeIdOnlyTask(cn("b"), 2, 2, cfg(FakeWin(5, 1, RECT))),
        )
        assertEquals(TaskModeState(1, 0), taskModeState(1) { tasks })
        assertEquals(TaskModeState(5, 2), taskModeState(2) { tasks })
        assertNull(taskModeState(99) { tasks })
    }

    @Test
    fun `taskModeState displayId is -1 when the field is absent`() {
        assertEquals(TaskModeState(5, -1), taskModeState(1) { listOf(FakeNoDisplayTask(cn("a"), 1, win())) })
    }

    @Test
    fun `taskModeState is null as a whole when displayId has the wrong type`() {
        assertNull(taskModeState(1) { listOf(FakeBadDisplayTask(cn("a"), 1, configuration = win())) })
    }

    @Test
    fun `taskModeState is null without a configuration`() {
        assertNull(taskModeState(1) { listOf(FakeNoConfigTask(cn("a"), 1)) })
        assertNull(taskModeState(1) { listOf(FakeBareTask(cn("a"), 1, 0, cfg(null))) })
    }

    // ---- taskActivityType ----

    @Test
    fun `taskActivityType returns the type of the task with that id or -1`() {
        val tasks = listOf(FakeTask(cn("a"), taskId = 1, configuration = cfg(FakeWin(5, 3, RECT))))
        assertEquals(3, taskActivityType(1) { tasks })
        assertEquals(-1, taskActivityType(99) { tasks })
    }

    @Test
    fun `taskActivityType is -1 for a broken chain or a throwing getter`() {
        assertEquals(-1, taskActivityType(1) { listOf(FakeNoConfigTask(cn("a"), 1)) })
        assertEquals(-1, taskActivityType(1) { listOf(FakeBareTask(cn("a"), 1, 0, cfg(null))) })
        assertEquals(-1, taskActivityType(1) { listOf(FakeBareTask(cn("a"), 1, 0, cfg(FakeWinTypeThrows()))) })
        assertEquals(-1, taskActivityType(1) { throw IllegalStateException("boom") })
    }
}
