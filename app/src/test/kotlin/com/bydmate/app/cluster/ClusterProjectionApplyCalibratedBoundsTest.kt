package com.bydmate.app.cluster

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDisplayManager

/**
 * Unit tests for [ClusterProjectionManager.applyCalibratedBoundsToTask] (Task M).
 *
 * Key scenario (I-1 fix): the native BYD "show on cluster" button moves a task to the cluster
 * display WITHOUT our direct freeform projection being active. Before the fix the function gated
 * on [ClusterProjectionManager.directDisplayId] which is -1 in this scenario, making the
 * function always a no-op. After the fix it gates on the id returned by
 * [resolveClusterDisplay], which works regardless of whether our projection is active.
 */
@RunWith(RobolectricTestRunner::class)
class ClusterProjectionApplyCalibratedBoundsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var addedDisplayId: Int = -1

    @Before
    fun setUp() {
        context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        // Add a virtual display named "XDJAScreenProjection_1" to make resolveClusterDisplay
        // find it by name. The returned id is used as taskDisplayId in the tests.
        addedDisplayId = ShadowDisplayManager.addDisplay("w1280dp-h480dp", "XDJAScreenProjection_1")
    }

    @After
    fun tearDown() {
        if (addedDisplayId != -1) ShadowDisplayManager.removeDisplay(addedDisplayId)
    }

    /**
     * REQUIRED I-1 test: bounds must be applied even when our direct cluster projection is NOT
     * active (directDisplayId == -1, the default). This is the exact production scenario: the
     * user pressed the navigator's native "show on cluster" button; our projection is off.
     *
     * Mutation evidence: removing the resolveClusterDisplay gate (reverting to the old
     * directDisplayId gate) causes this test to fail because directDisplayId stays -1 and the
     * function returns early before calling setTaskBounds.
     */
    @Test
    fun `applyCalibratedBoundsToTask applies bounds when direct projection is not active`() = runTest {
        // directDisplayId is -1 by default: ClusterProjectionManager has not started any
        // direct freeform projection. This is the scenario I-1 was written to fix.
        val helper = mockk<HelperClient>(relaxed = true)

        ClusterProjectionManager.applyCalibratedBoundsToTask(
            taskId = 99,
            taskDisplayId = addedDisplayId,
            context = context,
            helper = helper,
        )

        // With the I-1 fix (resolveClusterDisplay gate), bounds must be applied.
        coVerify(atLeast = 1) { helper.setTaskBounds(99, any(), any(), any(), any()) }
    }

    /**
     * Guard: a task on a display that is NOT the cluster display must not receive a bounds push.
     */
    @Test
    fun `applyCalibratedBoundsToTask is a no-op when task is on a different display`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val wrongDisplayId = addedDisplayId + 100

        ClusterProjectionManager.applyCalibratedBoundsToTask(
            taskId = 99,
            taskDisplayId = wrongDisplayId,
            context = context,
            helper = helper,
        )

        coVerify(exactly = 0) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
    }
}
