package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Android 10 head units (DiLink 3.0 / 4.0) move a task only together with its whole stack, so the
 * move is safe exactly when the stack holds that single task. A freeform task (the cluster and
 * split paths flip the mode first) owns its stack; the fullscreen tasks on the main display share
 * one, and moving that stack would drag every user window onto the cluster.
 */
class DecideStackMoveTest {

    @Test
    fun `single-task stack on another display is moved`() {
        val stacks = listOf(AtmStackEntry(stackId = 7, displayId = 0, taskIds = intArrayOf(42)))

        assertEquals(StackMoveDecision.Move(7), decideStackMove(stacks, taskId = 42, displayId = 2))
    }

    @Test
    fun `single-task stack already on the target display is left alone`() {
        val stacks = listOf(AtmStackEntry(stackId = 7, displayId = 2, taskIds = intArrayOf(42)))

        assertEquals(StackMoveDecision.AlreadyThere, decideStackMove(stacks, taskId = 42, displayId = 2))
    }

    @Test
    fun `stack shared with other tasks is refused`() {
        val stacks = listOf(AtmStackEntry(stackId = 1, displayId = 0, taskIds = intArrayOf(42, 43)))

        assertEquals(
            StackMoveDecision.Refuse("stack 1 shared by 2 tasks; refusing to move"),
            decideStackMove(stacks, taskId = 42, displayId = 2),
        )
    }

    /** A refusal reaches launchAndForce as its own type, so it aborts instead of being swallowed. */
    @Test
    fun `a refusal carries its reason into StackMoveRefused`() {
        val stacks = listOf(AtmStackEntry(stackId = 1, displayId = 0, taskIds = intArrayOf(42, 43)))
        val decision = decideStackMove(stacks, taskId = 42, displayId = 2) as StackMoveDecision.Refuse

        assertEquals(decision.reason, StackMoveRefused(decision.reason).message)
    }

    @Test
    fun `task in no stack is refused`() {
        val stacks = listOf(AtmStackEntry(stackId = 1, displayId = 0, taskIds = intArrayOf(43)))

        assertEquals(
            StackMoveDecision.Refuse("no stack for task=42"),
            decideStackMove(stacks, taskId = 42, displayId = 2),
        )
    }
}
