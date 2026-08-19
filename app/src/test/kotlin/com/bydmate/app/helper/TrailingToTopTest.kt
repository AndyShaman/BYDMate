package com.bydmate.app.helper

import android.os.Parcel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Mixed-version wire contract of the trailing toTop int of TX_SPLIT37_MOVE_TASK (the bounce):
 * a new daemon must survive a Parcel written by an app that predates the argument, where every
 * move was an onTop one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TrailingToTopTest {

    /** Parcel positioned as the handler leaves it: leading args consumed, [trailing] left (or not). */
    private fun parcelWith(trailing: Int?): Parcel {
        val p = Parcel.obtain()
        p.writeInt(77)   // stand-in for the args the handler already read
        if (trailing != null) p.writeInt(trailing)
        p.setDataPosition(0)
        p.readInt()
        return p
    }

    private fun read(trailing: Int?): Boolean {
        val p = parcelWith(trailing)
        try {
            return readTrailingToTop(p)
        } finally {
            p.recycle()
        }
    }

    @Test
    fun `absent trailing int means an old client and stays onTop`() {
        assertTrue(read(null))
    }

    @Test
    fun `zero is the bounce, which must not raise the task`() {
        assertFalse(read(0))
    }

    @Test
    fun `one is an ordinary onTop move`() {
        assertTrue(read(1))
    }
}
