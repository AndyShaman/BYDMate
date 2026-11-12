package com.bydmate.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterProjectionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #194: on a car whose cluster display only the daemon sees (DiLink 4.0) the projection always
 * runs the direct transport, yet the chooser showed «Заводской» as selected. The row and the scale
 * hints read what the projection actually uses; no pref is written.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectionModeRowTest {

    @Test fun `forced direct shows only extended whatever was chosen`() {
        assertEquals(ProjectionModeRow.OnlyExtended, projectionModeRow(directChosen = false, directForced = true))
        assertEquals(ProjectionModeRow.OnlyExtended, projectionModeRow(directChosen = true, directForced = true))
        assertTrue(projectionModeRow(directChosen = false, directForced = true).effectiveDirect)
    }

    @Test fun `not forced shows the chooser with the stored choice`() {
        val factory = projectionModeRow(directChosen = false, directForced = false)
        assertEquals(ProjectionModeRow.Chooser(directSelected = false), factory)
        assertFalse(factory.effectiveDirect)

        val extended = projectionModeRow(directChosen = true, directForced = false)
        assertEquals(ProjectionModeRow.Chooser(directSelected = true), extended)
        assertTrue(extended.effectiveDirect)
    }

    @Test fun `the forced marker is read from the projection prefs`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)

        prefs.edit().putBoolean(ClusterProjectionManager.KEY_DIRECT_FORCED, true).commit()
        assertTrue(ClusterProjectionManager.isDirectForced(context))

        prefs.edit().putBoolean(ClusterProjectionManager.KEY_DIRECT_FORCED, false).commit()
        assertFalse(ClusterProjectionManager.isDirectForced(context))
    }
}
