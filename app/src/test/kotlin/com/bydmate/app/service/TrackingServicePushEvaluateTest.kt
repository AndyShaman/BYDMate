package com.bydmate.app.service

import com.bydmate.app.data.push.FidPushApplier
import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The set of pushed fields that re-evaluate the automation rules on the event instead of on
 * the next poll tick. Touching the companion does NOT instantiate the Android Service — see
 * TrackingServiceButtonBridgeTest.
 */
class TrackingServicePushEvaluateTest {

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
