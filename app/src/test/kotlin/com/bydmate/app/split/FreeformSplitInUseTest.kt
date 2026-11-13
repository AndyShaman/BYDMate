package com.bydmate.app.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake that overrides only the abstract members, so isFreeformSplitInUse() is the default. */
private class AbstractOnlySplitPrefs(private val enabled: Boolean) : SplitPreferences {
    override fun getLastPair(): SplitPair? = null
    override fun saveLastPair(pair: SplitPair) = Unit
    override fun clearLastPair() = Unit
    override fun isFeatureEnabled(): Boolean = enabled
    override fun setFeatureEnabled(enabled: Boolean) = Unit
}

/** The split consumes enable_freeform_support only on the freeform path: feature on, not the
 *  native split mode and not the 3:7 engine of platformized firmware. */
class FreeformSplitInUseTest {

    @Test
    fun `freeformSplitInUse table`() {
        // feature, native, platformized -> expected. The Android level is deliberately no input:
        // the freeform path runs below Android 12 too (#385), so the flag is needed there as well.
        val rows = listOf(
            Row(true, false, false, true),
            Row(false, false, false, false),
            Row(true, true, false, false),
            Row(true, false, true, false),
        )
        for (r in rows) {
            assertEquals(
                r.toString(),
                r.expected,
                freeformSplitInUse(r.feature, r.native, r.platformized),
            )
        }
    }

    @Test
    fun `default isFreeformSplitInUse follows the feature switch when on`() {
        assertTrue(AbstractOnlySplitPrefs(enabled = true).isFreeformSplitInUse())
    }

    @Test
    fun `default isFreeformSplitInUse follows the feature switch when off`() {
        assertFalse(AbstractOnlySplitPrefs(enabled = false).isFreeformSplitInUse())
    }

    @Test
    fun `disabled split preferences are never in use`() {
        assertFalse(DisabledSplitPreferences.isFreeformSplitInUse())
    }

    private data class Row(
        val feature: Boolean,
        val native: Boolean,
        val platformized: Boolean,
        val expected: Boolean,
    )
}
