package com.bydmate.app.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake that overrides only the abstract members, so isFreeformSplitInUse() is the default. */
private class AbstractOnlySplitPrefs(private val enabled: Boolean) : SplitPreferences {
    override fun getLastPair(): SplitPair? = null
    override fun saveLastPair(pair: SplitPair) {}
    override fun clearLastPair() {}
    override fun isFeatureEnabled(): Boolean = enabled
    override fun setFeatureEnabled(enabled: Boolean) {}
}

/** The split consumes enable_freeform_support only on the freeform path: feature on, not the
 *  native split mode, not the 3:7 engine of platformized firmware, and an SDK the split runs on. */
class FreeformSplitInUseTest {

    @Test
    fun `freeformSplitInUse table`() {
        // feature, native, platformized, sdk -> expected
        val rows = listOf(
            Row(true, false, false, 32, true),
            Row(true, false, false, 31, true),
            Row(true, false, false, 30, false),
            Row(true, false, false, 29, false),
            Row(false, false, false, 32, false),
            Row(true, true, false, 32, false),
            Row(true, false, true, 32, false),
        )
        for (r in rows) {
            assertEquals(
                r.toString(),
                r.expected,
                freeformSplitInUse(r.feature, r.native, r.platformized, r.sdk),
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
        val sdk: Int,
        val expected: Boolean,
    )
}
