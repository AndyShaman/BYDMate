package com.bydmate.app.service

import com.bydmate.app.data.push.FidPushApplier
import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The set of pushed fields that re-evaluate the automation rules on the event instead of on
 * the next poll tick. Touching the companion does NOT instantiate the Android Service — see
 * TrackingServiceSnapshotReuseTest.
 */
class TrackingServicePushEvaluateTest {

    @Test fun `a discrete field evaluates on the push`() {
        assertTrue("gear" in TrackingService.PUSH_EVALUATE_FIELDS)
        assertTrue("turnSignal" in TrackingService.PUSH_EVALUATE_FIELDS)
        assertTrue("lightLevel" in TrackingService.PUSH_EVALUATE_FIELDS)
    }

    /** A field no trigger reads would only buy an evaluate no rule can act on. */
    @Test fun `a field without a trigger param stays out`() {
        assertFalse("frontTrunk" in TrackingService.PUSH_EVALUATE_FIELDS)
        assertFalse("chargerConnectState" in TrackingService.PUSH_EVALUATE_FIELDS)
    }

    @Test fun `a continuous field stays on the poll tick`() {
        assertFalse("speed" in TrackingService.PUSH_EVALUATE_FIELDS)
        assertFalse("soc" in TrackingService.PUSH_EVALUATE_FIELDS)
        assertFalse("power" in TrackingService.PUSH_EVALUATE_FIELDS)
    }

    /**
     * A name that FidPushApplier does not patch would never reach the snapshot, so the
     * evaluate would run on unchanged data — the set must not rot when the applier changes.
     * apply() returns null for exactly those names (0 passes every guard of these fields).
     */
    @Test fun `every field of the set is patched by the applier`() {
        for (field in TrackingService.PUSH_EVALUATE_FIELDS) {
            assertNotNull(
                "$field is not patched by FidPushApplier",
                FidPushApplier.apply(diParsData(), field, 0, 0.0),
            )
        }
    }
}
