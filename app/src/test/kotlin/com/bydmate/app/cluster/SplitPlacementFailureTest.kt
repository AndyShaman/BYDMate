package com.bydmate.app.cluster

import com.bydmate.app.data.vehicle.SplitTaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #288: a VD projection that started from a native split pane is only active once the task is on the VD. */
class SplitPlacementFailureTest {

    private fun state(mode: Int, display: Int, taskId: Int = 9) =
        SplitTaskState(taskId, mode, 0, 0, 0, 0, display)

    @Test fun `fullscreen before the move - accepted as today whatever the read says`() {
        assertNull(splitPlacementFailure(state(1, 0), state(1, 0), vdId = 7))
        assertNull(splitPlacementFailure(state(1, 0), null, vdId = 7))
    }

    @Test fun `no task or unknown state before the move - accepted as today`() {
        assertNull(splitPlacementFailure(null, state(4, 0), vdId = 7))
        assertNull(splitPlacementFailure(state(0, 0, taskId = -1), state(4, 0), vdId = 7))
    }

    @Test fun `split pane before and on the VD after - active`() {
        assertNull(splitPlacementFailure(state(4, 0), state(1, 7), vdId = 7))
        assertNull(splitPlacementFailure(state(3, 0), state(1, 7), vdId = 7))
    }

    @Test fun `split pane before and still on the main display after - failure`() {
        assertEquals("split: task_display=0 wm=4", splitPlacementFailure(state(4, 0), state(4, 0), vdId = 7))
    }

    @Test fun `split pane before and the task gone after - failure`() {
        assertEquals("split: task=none", splitPlacementFailure(state(3, 0), state(0, 0, taskId = -1), vdId = 7))
    }

    @Test fun `split pane before and the daemon silent after - accepted, the verify line reports it`() {
        assertNull(splitPlacementFailure(state(4, 0), null, vdId = 7))
    }
}
