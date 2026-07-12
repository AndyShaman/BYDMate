package com.bydmate.app.helper

import android.os.Parcel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperDaemonBatchTest {

    private fun request(vararg triples: Triple<Int, Int, Int>): Parcel {
        val p = Parcel.obtain()
        p.writeInt(triples.size)
        triples.forEach { (tx, dev, fid) -> p.writeInt(tx); p.writeInt(dev); p.writeInt(fid) }
        p.setDataPosition(0)
        return p
    }

    @Test
    fun `happy path returns one status-value pair per item in order`() {
        val data = request(
            Triple(5, 1014, 1246777400),
            Triple(7, 1013, -1807745016),
        )
        val reply = Parcel.obtain()
        try {
            readBatchIntoReply(data, reply) { tx, dev, fid ->
                // echo scheme: status = tx, value = dev + fid lets us assert routing
                tx to (dev + fid)
            }
            reply.setDataPosition(0)
            assertEquals(2, reply.readInt())
            assertEquals(5, reply.readInt())
            assertEquals(1014 + 1246777400, reply.readInt())
            assertEquals(7, reply.readInt())
            assertEquals(1013 + (-1807745016), reply.readInt())
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    @Test
    fun `throwing transact yields -998 pair and does not abort the rest`() {
        val data = request(Triple(5, 1, 1), Triple(5, 2, 2))
        val reply = Parcel.obtain()
        try {
            readBatchIntoReply(data, reply) { _, dev, _ ->
                if (dev == 1) throw RuntimeException("boom") else 0 to 42
            }
            reply.setDataPosition(0)
            assertEquals(2, reply.readInt())
            assertEquals(-998, reply.readInt())
            assertEquals(0, reply.readInt())
            assertEquals(0, reply.readInt())
            assertEquals(42, reply.readInt())
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    @Test
    fun `count zero replies zero pairs`() {
        val data = Parcel.obtain()
        data.writeInt(0)
        data.setDataPosition(0)
        val reply = Parcel.obtain()
        try {
            readBatchIntoReply(data, reply) { _, _, _ -> 0 to 0 }
            reply.setDataPosition(0)
            assertEquals(0, reply.readInt())
            assertEquals(0, reply.dataAvail())
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    @Test
    fun `count above cap replies zero pairs`() {
        val data = Parcel.obtain()
        data.writeInt(HelperBinderProtocol.MAX_BATCH_ITEMS + 1)
        data.setDataPosition(0)
        val reply = Parcel.obtain()
        try {
            var called = false
            readBatchIntoReply(data, reply) { _, _, _ -> called = true; 0 to 0 }
            reply.setDataPosition(0)
            assertEquals(0, reply.readInt())
            assertEquals(false, called)
        } finally {
            data.recycle(); reply.recycle()
        }
    }
}
