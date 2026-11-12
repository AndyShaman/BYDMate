package com.bydmate.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.split.DisabledSplitPreferences
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The cluster code computes enable_freeform_support from the split state, so the split
 *  preferences the DI container provides must be the ones ClusterProjectionManager reads. */
@RunWith(RobolectricTestRunner::class)
class SplitPreferencesWiringTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        // Process-wide state: leave it as every other test expects it.
        ClusterProjectionManager.splitPreferences = DisabledSplitPreferences
    }

    @Test
    fun `provideSplitPreferences hands its instance to the cluster code`() {
        val provided = AppModule.provideSplitPreferences(context)
        assertSame(provided, ClusterProjectionManager.splitPreferences)
    }
}
