package com.bydmate.app.helper

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mixed-version wire contract of the trailing activityType int (split touch fix, 392):
 * a new daemon must survive a Parcel written by an app that predates it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TrailingPaneTypeTest {

    /** Parcel positioned as the handler leaves it: leading args consumed, [trailing] left (or not). */
    private fun parcelWith(trailing: Int?): Parcel {
        val p = Parcel.obtain()
        p.writeInt(77)   // stand-in for the args the handler already read
        if (trailing != null) p.writeInt(trailing)
        p.setDataPosition(0)
        p.readInt()
        return p
    }

    private fun read(trailing: Int?): Int {
        val p = parcelWith(trailing)
        try {
            return readTrailingPaneType(p)
        } finally {
            p.recycle()
        }
    }

    @Test
    fun `absent trailing int means an old client and stays RECENTS`() {
        assertEquals(ACTIVITY_TYPE_RECENTS, read(null))
    }

    @Test
    fun `STANDARD is carried through`() {
        assertEquals(ACTIVITY_TYPE_STANDARD, read(ACTIVITY_TYPE_STANDARD))
    }

    @Test
    fun `RECENTS is carried through`() {
        assertEquals(ACTIVITY_TYPE_RECENTS, read(ACTIVITY_TYPE_RECENTS))
    }

    @Test
    fun `any other activityType is coerced to RECENTS`() {
        assertEquals(ACTIVITY_TYPE_RECENTS, read(2))    // ACTIVITY_TYPE_HOME
        assertEquals(ACTIVITY_TYPE_RECENTS, read(0))    // ACTIVITY_TYPE_UNDEFINED
        assertEquals(ACTIVITY_TYPE_RECENTS, read(-1))
    }
}
