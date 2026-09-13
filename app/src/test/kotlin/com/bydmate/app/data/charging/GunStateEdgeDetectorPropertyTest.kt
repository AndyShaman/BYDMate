package com.bydmate.app.data.charging

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// Real bug class: phantom "gun disconnected" edges from sentinel/null autoservice reads
// created fake charging sessions. These properties pin down that nulls are truly invisible
// to the detector and that an edge can only ever come from a real non-NONE -> NONE transition.
class GunStateEdgeDetectorPropertyTest {

    private val gunState = Arb.int(1, 5).orNull()
    private val readings = Arb.list(gunState, 0..200)

    @Test fun `nulls are invisible - filtered replay matches original with false at null positions`(): Unit = runBlocking {
        checkAll(readings) { samples ->
            val detector = GunStateEdgeDetector()
            val fullResults = samples.map { detector.onSample(it) }

            val filteredDetector = GunStateEdgeDetector()
            val filteredResults = samples.filterNotNull().map { filteredDetector.onSample(it) }

            var filteredIndex = 0
            val reconstructed = samples.map { sample ->
                if (sample == null) false else filteredResults[filteredIndex++]
            }
            assertEquals(fullResults, reconstructed)
        }
    }

    @Test fun `edges fired equal non-NONE to NONE transitions in the null-filtered sequence`(): Unit = runBlocking {
        checkAll(readings) { samples ->
            val detector = GunStateEdgeDetector()
            val edgeCount = samples.count { detector.onSample(it) }

            val nonNull = samples.filterNotNull()
            var expected = 0
            for (i in 1 until nonNull.size) {
                if (nonNull[i - 1] != GunStateEdgeDetector.GUN_STATE_NONE && nonNull[i] == GunStateEdgeDetector.GUN_STATE_NONE) {
                    expected++
                }
            }
            assertEquals(expected, edgeCount)
        }
    }

    @Test fun `onSample never fires with no known prior state or a NONE prior state`(): Unit = runBlocking {
        checkAll(readings) { samples ->
            val detector = GunStateEdgeDetector()
            var lastNonNull: Int? = null
            for (sample in samples) {
                val fired = detector.onSample(sample)
                if (sample != null && (lastNonNull == null || lastNonNull == GunStateEdgeDetector.GUN_STATE_NONE)) {
                    assertFalse("fired with no prior known state or NONE prior (prev=$lastNonNull, current=$sample)", fired)
                }
                if (sample != null) lastNonNull = sample
            }
        }
    }
}
