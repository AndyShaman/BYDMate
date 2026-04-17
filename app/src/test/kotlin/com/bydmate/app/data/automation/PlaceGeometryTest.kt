package com.bydmate.app.data.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceGeometryTest {

    @Test
    fun `same point is zero distance`() {
        val d = PlaceGeometry.distanceMeters(55.7558, 37.6173, 55.7558, 37.6173)
        assertEquals(0.0, d, 1e-6)
    }

    @Test
    fun `moscow red square to saint basils cathedral is roughly 217m`() {
        // Red Square (55.7539, 37.6208) → Saint Basil's Cathedral (55.7525, 37.6231)
        val d = PlaceGeometry.distanceMeters(55.7539, 37.6208, 55.7525, 37.6231)
        assertEquals(217.0, d, 20.0)
    }

    @Test
    fun `one degree longitude at equator is roughly 111320m`() {
        val d = PlaceGeometry.distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_320.0, d, 500.0)
    }

    @Test
    fun `one degree latitude is roughly 111320m`() {
        val d = PlaceGeometry.distanceMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_320.0, d, 500.0)
    }

    @Test
    fun `distance is symmetric`() {
        val ab = PlaceGeometry.distanceMeters(55.7539, 37.6208, 55.7525, 37.6231)
        val ba = PlaceGeometry.distanceMeters(55.7525, 37.6231, 55.7539, 37.6208)
        assertEquals(ab, ba, 1e-6)
    }

    @Test
    fun `isInside returns true when point is within radius`() {
        // ~40 m north of origin at equator
        val lat2 = 40.0 / 111_320.0
        assertTrue(PlaceGeometry.isInside(lat2, 0.0, 0.0, 0.0, 50))
    }

    @Test
    fun `isInside returns false when point is outside radius`() {
        // ~80 m north of origin at equator
        val lat2 = 80.0 / 111_320.0
        assertFalse(PlaceGeometry.isInside(lat2, 0.0, 0.0, 0.0, 50))
    }

    @Test
    fun `isInside returns true when distance equals radius (boundary is inclusive)`() {
        // Same point: distance == 0 <= any positive radius
        assertTrue(PlaceGeometry.isInside(55.7558, 37.6173, 55.7558, 37.6173, 50))
    }
}
