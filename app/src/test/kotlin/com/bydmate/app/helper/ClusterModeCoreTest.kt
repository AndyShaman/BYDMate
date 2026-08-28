package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterModeCoreTest {

    private class Recorder(private val results: MutableList<Boolean> = mutableListOf()) {
        val cmds = mutableListOf<Int>()
        val sleeps = mutableListOf<Long>()
        val call: (Int) -> Boolean = { cmd ->
            cmds += cmd
            if (results.isEmpty()) true else results.removeAt(0)
        }
        val sleep: (Long) -> Unit = { sleeps += it }
    }

    @Test
    fun `mode on issues 16 alone`() {
        val rec = Recorder()
        assertTrue(clusterModeCore(HelperBinderProtocol.CLUSTER_MODE_ON, rec.call, rec.sleep))
        assertEquals(listOf(16), rec.cmds)
        assertEquals(0, rec.sleeps.size)
    }

    @Test
    fun `detach only issues exactly one call with 18 and never sleeps`() {
        val rec = Recorder()
        assertTrue(clusterModeCore(HelperBinderProtocol.CLUSTER_MODE_DETACH_ONLY, rec.call, rec.sleep))
        assertEquals(listOf(18), rec.cmds)
        assertEquals(0, rec.sleeps.size)
    }

    @Test
    fun `detach only reports the failed call`() {
        val rec = Recorder(mutableListOf(false))
        assertFalse(clusterModeCore(HelperBinderProtocol.CLUSTER_MODE_DETACH_ONLY, rec.call, rec.sleep))
        assertEquals(listOf(18), rec.cmds)
    }

    @Test
    fun `mode off keeps the 18 - pause - 0 sequence`() {
        val rec = Recorder()
        assertTrue(clusterModeCore(HelperBinderProtocol.CLUSTER_MODE_OFF, rec.call, rec.sleep))
        assertEquals(listOf(18, 0), rec.cmds)
        assertEquals(listOf(1000L), rec.sleeps)
    }

    @Test
    fun `mode off is false when the detach fails`() {
        val rec = Recorder(mutableListOf(false, true))
        assertFalse(clusterModeCore(HelperBinderProtocol.CLUSTER_MODE_OFF, rec.call, rec.sleep))
        assertEquals(listOf(18, 0), rec.cmds)
    }
}
