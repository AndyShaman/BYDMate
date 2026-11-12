package com.bydmate.app.helper

import android.os.IBinder
import android.os.Parcel

/**
 * Performs a single autoservice Binder transact and returns (status, retInt).
 *
 * [tx] is the transact code (5=getInt, 6=setInt, 7=getDouble raw int, 9=getBuffer).
 * [writeValue] controls whether [value] is written into the parcel (true for setInt/tx=6 only).
 *
 * Semantics match the proven logic from the TCP daemon:
 *   avail >= 4 → status = reply.readInt() else -999
 *   avail >= 8 → retInt = reply.readInt() else 0
 */
internal fun autoserviceTransact(
    svc: IBinder,
    autoIface: String,
    tx: Int,
    dev: Int,
    fid: Int,
    value: Int,
    writeValue: Boolean
): Pair<Int, Int> {
    val data2 = Parcel.obtain()
    val reply2 = Parcel.obtain()
    return try {
        data2.writeInterfaceToken(autoIface)
        data2.writeInt(dev)
        data2.writeInt(fid)
        if (writeValue) data2.writeInt(value)
        svc.transact(tx, data2, reply2, 0)
        val avail = reply2.dataAvail()
        val status = if (avail >= 4) reply2.readInt() else -999
        val retInt = if (avail >= 8) reply2.readInt() else 0
        status to retInt
    } finally {
        data2.recycle()
        reply2.recycle()
    }
}

/**
 * Buffer sibling of [autoserviceTransact]: autoservice setBuffer (tx 14), whose parcel carries a
 * byte array instead of an int. [bytes] goes out exactly as given; the instrument's road-name
 * field wants UTF-16LE without a BOM, and that encoding is the caller's business.
 *
 * Same (status, retInt) semantics as [autoserviceTransact].
 */
internal fun autoserviceTransactBuffer(
    svc: IBinder,
    autoIface: String,
    dev: Int,
    fid: Int,
    bytes: ByteArray
): Pair<Int, Int> {
    val data2 = Parcel.obtain()
    val reply2 = Parcel.obtain()
    return try {
        data2.writeInterfaceToken(autoIface)
        data2.writeInt(dev)
        data2.writeInt(fid)
        data2.writeByteArray(bytes)
        svc.transact(14, data2, reply2, 0)
        val avail = reply2.dataAvail()
        val status = if (avail >= 4) reply2.readInt() else -999
        val retInt = if (avail >= 8) reply2.readInt() else 0
        status to retInt
    } finally {
        data2.recycle()
        reply2.recycle()
    }
}

/**
 * TX_READ_BATCH body. Reads `count` then count × (tx, dev, fid) triples from [data],
 * invokes [doTransact] per triple, writes `count` then count × (status, value) pairs
 * to [reply]. A throwing [doTransact] yields (-998, 0) for that item and the batch
 * continues — one bad fid must not poison the other 57. Invalid count → single 0.
 */
internal fun readBatchIntoReply(
    data: Parcel,
    reply: Parcel?,
    doTransact: (tx: Int, dev: Int, fid: Int) -> Pair<Int, Int>
) {
    val n = data.readInt()
    if (n < 1 || n > HelperBinderProtocol.MAX_BATCH_ITEMS) {
        reply?.writeInt(0)
        return
    }
    val triples = IntArray(n * 3)
    for (i in 0 until n * 3) triples[i] = data.readInt()
    reply?.writeInt(n)
    for (i in 0 until n) {
        val (status, retInt) = runCatching {
            doTransact(triples[i * 3], triples[i * 3 + 1], triples[i * 3 + 2])
        }.getOrElse { -998 to 0 }
        reply?.writeInt(status)
        reply?.writeInt(retInt)
    }
}
