package com.bydmate.app.data.subscription

import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.nativestack.FidAddress
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.nativestack.FidMap
import com.bydmate.app.data.nativestack.FidResolutionNote
import com.bydmate.app.data.nativestack.ResolvedFidTable
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The subscriptions are registered at service start, before the firmware catalog has been
 * read, so they must follow it afterwards. Registration itself fails here (no BYD SDK on
 * the JVM) — what the test checks is which addresses the channels were built on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FidSubscriptionManagerTest {

    @After fun tearDown() = FidAddresses.resetToConstants()

    private fun manager(): FidSubscriptionManager =
        FidSubscriptionManager(ApplicationProvider.getApplicationContext())
            .also { it.testBuild = true }

    /** Table that is the constants everywhere except [field], which moves to [fid]. */
    private fun tableWith(field: String, fid: Int): ResolvedFidTable {
        val addresses = FidMap.all.associate {
            it.field to FidAddress(it.device, if (it.field == field) fid else it.fid)
        }
        return ResolvedFidTable(addresses, emptyList<FidResolutionNote>(), "test")
    }

    @Test fun `a moved turn signal fid re-registers the channels`() {
        val manager = manager()
        manager.start()
        assertTrue(manager.diagnosticsSnapshot().any { it.startsWith("blink/950009900:") })

        FidAddresses.install(tableWith("turnSignal", 1564))
        manager.restartForResolvedAddresses()

        assertTrue(manager.diagnosticsSnapshot().any { it.startsWith("blink/1564:") })
    }

    @Test fun `an unchanged table leaves the channels alone`() {
        val manager = manager()
        manager.start()
        val before = manager.diagnosticsSnapshot()

        FidAddresses.install(tableWith("turnSignal", FidMap.byField.getValue("turnSignal").fid))
        manager.restartForResolvedAddresses()

        assertTrue(manager.diagnosticsSnapshot() == before)
    }

    @Test fun `nothing happens when the subscriptions were never started`() {
        val manager = manager()
        FidAddresses.install(tableWith("turnSignal", 1564))
        manager.restartForResolvedAddresses()
        assertTrue(manager.diagnosticsSnapshot() == listOf("not started"))
    }
}
