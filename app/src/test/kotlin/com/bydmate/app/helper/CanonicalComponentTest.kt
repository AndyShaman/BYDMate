package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Pure JVM tests for canonicalComponent — the dedup core that lets the a11y enable op strip BOTH the
 * short and full spellings of our service before re-adding one, so a crashed service actually
 * re-binds. A literal-string filter missed the short form and the toggle became a no-op (the reboot
 * regression).
 */
class CanonicalComponentTest {

    private val short = "com.bydmate.app/.cluster.SteeringWheelKeyService"
    private val full = "com.bydmate.app/com.bydmate.app.cluster.SteeringWheelKeyService"

    @Test
    fun `short form expands the leading dot to the package`() {
        assertEquals(full, canonicalComponent(short))
    }

    @Test
    fun `full form is unchanged`() {
        assertEquals(full, canonicalComponent(full))
    }

    @Test
    fun `short and full forms canonicalise to the same component`() {
        assertEquals(canonicalComponent(short), canonicalComponent(full))
    }

    @Test
    fun `a token without a slash is returned unchanged`() {
        assertEquals("garbage", canonicalComponent("garbage"))
    }

    @Test
    fun `a different service does not collide with ours`() {
        val other = "com.byd.airconditioning/.gesture.AcGestureService"
        assertNotEquals(canonicalComponent(full), canonicalComponent(other))
    }
}
