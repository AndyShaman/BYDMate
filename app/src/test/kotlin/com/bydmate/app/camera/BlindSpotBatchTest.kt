package com.bydmate.app.camera

import com.bydmate.app.data.nativestack.FidAddress
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.nativestack.FidMap
import com.bydmate.app.data.nativestack.ResolvedFidTable
import com.bydmate.app.data.vehicle.BatchReadItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fast loop must ask for its telemetry at the addresses the catalog resolved, not at the
 * Leopard 3 constants. Field case (Song DiLink 4.0, dump 2026-09-18): the firmware keeps
 * `Speed.SPEED_AUTO_SPEED` under fid 303038472, the compiled constant answered a sentinel, the
 * snapshot never became valid and the loss watchdog closed the camera on every tick.
 */
class BlindSpotBatchTest {

    /** Speed fid this firmware uses, from the `fid resolve` section of that dump. */
    private val songSpeedFid = 303038472

    @After fun tearDown() {
        FidAddresses.resetToConstants()
    }

    private fun installMoved(field: String, fid: Int) {
        val moved = FidMap.all.associate { entry ->
            entry.field to FidAddress(
                entry.device,
                if (entry.field == field) fid else entry.fid,
            )
        }
        FidAddresses.install(ResolvedFidTable(moved, emptyList(), "test"))
    }

    @Test fun `the batch follows a catalog-moved speed address`() {
        installMoved("speed", songSpeedFid)

        val items = blindSpotBatchItems(withBsd = false)

        assertEquals(BatchReadItem(BLIND_SPOT_TX_FLOAT, 1013, songSpeedFid), items[1])
    }

    @Test fun `the constants come back once the resolved table is dropped`() {
        installMoved("speed", songSpeedFid)
        FidAddresses.resetToConstants()

        val items = blindSpotBatchItems(withBsd = false)

        assertEquals(BatchReadItem(BLIND_SPOT_TX_FLOAT, 1013, -1807745016), items[1])
    }

    /** Order is load-bearing: the controller decodes the answer by fixed INDEX_* positions. */
    @Test fun `the core batch is turn signal, speed, gear in that order`() {
        val items = blindSpotBatchItems(withBsd = false)

        assertEquals(
            listOf(
                BatchReadItem(BLIND_SPOT_TX_INT, 1004, 950009900),
                BatchReadItem(BLIND_SPOT_TX_FLOAT, 1013, -1807745016),
                BatchReadItem(BLIND_SPOT_TX_INT, 1011, 555745336),
            ),
            items,
        )
    }

    @Test fun `the glow batch appends both BSD fids`() {
        val items = blindSpotBatchItems(withBsd = true)

        assertEquals(blindSpotBatchItems(withBsd = false), items.take(3))
        assertEquals(
            listOf(
                BatchReadItem(BLIND_SPOT_TX_INT, 1038, 1098907664),
                BatchReadItem(BLIND_SPOT_TX_INT, 1038, 1098907666),
            ),
            items.drop(3),
        )
    }
}
