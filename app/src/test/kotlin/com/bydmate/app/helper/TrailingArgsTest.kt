package com.bydmate.app.helper

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mixed-version wire contract of the trailing ints a new daemon reads after the leading args:
 * a new daemon must survive a Parcel written by an app that predates the argument.
 * - activityType of the split touch fix (392);
 * - toTop of TX_SPLIT37_MOVE_TASK (the bounce), where every old move was an onTop one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TrailingArgsTest {

    /** Parcel positioned as the handler leaves it: leading args consumed, [trailing] left (or not). */
    private fun <T> readWith(trailing: Int?, reader: (Parcel) -> T): T {
        val p = Parcel.obtain()
        p.writeInt(77)   // stand-in for the args the handler already read
        if (trailing != null) p.writeInt(trailing)
        p.setDataPosition(0)
        p.readInt()
        try {
            return reader(p)
        } finally {
            p.recycle()
        }
    }

    private fun paneType(trailing: Int?): Int = readWith(trailing, ::readTrailingPaneType)

    private fun toTop(trailing: Int?): Boolean = readWith(trailing, ::readTrailingToTop)

    // --- activityType ---

    @Test
    fun `absent trailing int means an old client and stays RECENTS`() {
        assertEquals(ACTIVITY_TYPE_RECENTS, paneType(null))
    }

    @Test
    fun `STANDARD is carried through`() {
        assertEquals(ACTIVITY_TYPE_STANDARD, paneType(ACTIVITY_TYPE_STANDARD))
    }

    @Test
    fun `RECENTS is carried through`() {
        assertEquals(ACTIVITY_TYPE_RECENTS, paneType(ACTIVITY_TYPE_RECENTS))
    }

    @Test
    fun `any other activityType is coerced to RECENTS`() {
        assertEquals(ACTIVITY_TYPE_RECENTS, paneType(2))    // ACTIVITY_TYPE_HOME
        assertEquals(ACTIVITY_TYPE_RECENTS, paneType(0))    // ACTIVITY_TYPE_UNDEFINED
        assertEquals(ACTIVITY_TYPE_RECENTS, paneType(-1))
    }

    // --- toTop ---

    @Test
    fun `absent trailing int means an old client and stays onTop`() {
        assertTrue(toTop(null))
    }

    @Test
    fun `zero is the bounce, which must not raise the task`() {
        assertFalse(toTop(0))
    }

    @Test
    fun `one is an ordinary onTop move`() {
        assertTrue(toTop(1))
    }
}
