package com.bydmate.app.data.vehicle

import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientBinderTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Minimal IBinder implementation that does NOT extend android.os.Binder
     * (transact is final there). All abstract methods stubbed; transact()
     * is implemented directly by subclasses / lambdas.
     */
    private abstract class FakeIBinder : IBinder {
        override fun isBinderAlive(): Boolean = true
        override fun pingBinder(): Boolean = true
        override fun getInterfaceDescriptor(): String = HelperBinderProtocol.DESCRIPTOR
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        @Suppress("OVERRIDE_DEPRECATION")
        override fun dump(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    /**
     * Builds a live fake binder that writes [status] and optionally [value] to reply,
     * then resets the reply position to 0 (so the client reads from the start).
     */
    private fun liveFake(status: Int, value: Int = 0): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply!!.writeInt(status)
            if (code != HelperBinderProtocol.TX_PING) reply.writeInt(value)
            reply.setDataPosition(0)
            return true
        }
    }

    /**
     * Binder whose transact() returns false (rejected / not handled), without
     * writing anything to reply.
     */
    private val rejectingFake: IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
    }

    /**
     * Binder whose transact() throws DeadObjectException (simulates a stale binder).
     */
    private val deadFake: IBinder = object : FakeIBinder() {
        override fun isBinderAlive(): Boolean = false
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            throw DeadObjectException("test dead binder")
    }

    /**
     * Live fake that hands the request args (after the interface token) to [capture]
     * before replying with (status=0, value=0).
     */
    private fun capturingFake(capture: (Parcel) -> Unit): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.setDataPosition(0)
            data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
            capture(data)
            reply!!.writeInt(0); reply.writeInt(0)
            reply.setDataPosition(0)
            return true
        }
    }

    /** Creates a HelperClientImpl with [resolveBinder] overridden to return [binder]. */
    private fun clientWith(binder: IBinder?): HelperClientImpl = object : HelperClientImpl() {
        override fun resolveBinder(): IBinder? = binder
    }

    // ---------------------------------------------------------------------------
    // Test cases
    // ---------------------------------------------------------------------------

    /** TC-1: read returns value when status == 0 */
    @Test
    fun `read returns value when status is 0`() = runBlocking {
        val client = clientWith(liveFake(status = 0, value = 42))
        val result = client.read(dev = 1014, fid = 1246777400, tx = 5)
        assertEquals(42L, result)
    }

    /** TC-2: read returns null when status != 0 (readAccepted only on 0) */
    @Test
    fun `read returns null when status is non-zero`() = runBlocking {
        val clientStatus1 = clientWith(liveFake(status = 1, value = 42))
        assertNull(clientStatus1.read(dev = 1014, fid = 1246777400))

        val clientStatusNeg = clientWith(liveFake(status = -1, value = 42))
        assertNull(clientStatusNeg.read(dev = 1014, fid = 1246777400))
    }

    /** TC-3: write returns true when status >= 0 */
    @Test
    fun `write returns true when status is non-negative`() = runBlocking {
        val clientStatus0 = clientWith(liveFake(status = 0))
        assertTrue(clientStatus0.write(dev = 1000, fid = 501219357, value = 1))

        val clientStatus1 = clientWith(liveFake(status = 1))
        assertTrue(clientStatus1.write(dev = 1000, fid = 501219357, value = 1))
    }

    /** TC-4: write returns false when status < 0 */
    @Test
    fun `write returns false when status is negative`() = runBlocking {
        val client = clientWith(liveFake(status = -1))
        assertFalse(client.write(dev = 1000, fid = 501219357, value = 1))
    }

    /** TC-5: isAlive true when ping status == 0, false otherwise */
    @Test
    fun `isAlive reflects ping status`() = runBlocking {
        val aliveClient = clientWith(liveFake(status = 0))
        assertTrue(aliveClient.isAlive())

        val deadClient = clientWith(liveFake(status = -999))
        assertFalse(deadClient.isAlive())
    }

    /** TC-6: transact returning false yields null for read and false for write */
    @Test
    fun `transact returning false yields null for read and false for write`() = runBlocking {
        val client = clientWith(rejectingFake)
        assertNull(client.read(dev = 1014, fid = 1246777400))
        assertFalse(client.write(dev = 1000, fid = 501219357, value = 1))
    }

    /**
     * TC-7: DeadObjectException retry — resolveBinder() is called exactly twice:
     * once returns the dead binder (throws DeadObjectException), once returns a
     * live binder (status=0, value=7). The single read() call returns 7L.
     */
    @Test
    fun `DeadObjectException triggers retry and resolveBinder called twice`() = runBlocking {
        var resolveCount = 0
        val client = object : HelperClientImpl() {
            override fun resolveBinder(): IBinder? {
                resolveCount++
                return if (resolveCount == 1) deadFake else liveFake(status = 0, value = 7)
            }
        }

        val result = client.read(dev = 1014, fid = 1246777400, tx = 5)
        assertEquals(7L, result)
        assertEquals("resolveBinder should be called exactly twice", 2, resolveCount)
    }

    // ---------------------------------------------------------------------------
    // writeStatus tests (TC-8 to TC-11)
    // ---------------------------------------------------------------------------

    /** TC-8: writeStatus returns the RAW status forwarded by the binder (status=1 real action). */
    @Test
    fun `writeStatus returns raw status 1 from binder`() = runBlocking {
        val client = clientWith(liveFake(status = 1))
        val result = client.writeStatus(dev = 1000, fid = 501219357, value = 1)
        assertEquals(1, result)
    }

    /** TC-9: writeStatus returns raw status 0 (no-op) unmodified. */
    @Test
    fun `writeStatus returns raw status 0 from binder`() = runBlocking {
        val client = clientWith(liveFake(status = 0))
        val result = client.writeStatus(dev = 1000, fid = 501219357, value = 1)
        assertEquals(0, result)
    }

    /** TC-10: writeStatus returns raw negative status (error code) unmodified. */
    @Test
    fun `writeStatus returns raw negative status from binder`() = runBlocking {
        val client = clientWith(liveFake(status = -10011))
        val result = client.writeStatus(dev = 1000, fid = 501219357, value = 1)
        assertEquals(-10011, result)
    }

    /** TC-11a: writeStatus returns null when daemon is unreachable (binder == null). */
    @Test
    fun `writeStatus returns null when binder is null`() = runBlocking {
        val client = clientWith(null)
        assertNull(client.writeStatus(dev = 1000, fid = 501219357, value = 1))
    }

    /** TC-11b: writeStatus returns null when transact returns false (rejected). */
    @Test
    fun `writeStatus returns null when transact is rejected`() = runBlocking {
        val client = clientWith(rejectingFake)
        assertNull(client.writeStatus(dev = 1000, fid = 501219357, value = 1))
    }

    // ---------------------------------------------------------------------------
    // TX_GET_TASK_STATE tests (TC-12 to TC-15)
    // ---------------------------------------------------------------------------

    /** Writes a TX_GET_TASK_STATE reply: status + taskId + windowingMode + l + t + r + b. */
    private fun taskStateFake(status: Int, taskId: Int, windowingMode: Int,
                               left: Int, top: Int, right: Int, bottom: Int): IBinder =
        object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeInt(status)
                if (status == 0) {
                    reply.writeInt(taskId)
                    reply.writeInt(windowingMode)
                    reply.writeInt(left)
                    reply.writeInt(top)
                    reply.writeInt(right)
                    reply.writeInt(bottom)
                } else {
                    reply.writeInt(0)  // trailing pad (existing convention)
                }
                reply.setDataPosition(0)
                return true
            }
        }

    /** TC-12: getTaskState returns SplitTaskState for a running task (status 0, taskId > 0). */
    @Test
    fun `getTaskState returns SplitTaskState when task is running`() = runBlocking {
        val client = clientWith(taskStateFake(0, 42, 1, 0, 0, 960, 600))
        val result = client.getTaskState("com.example.app")
        assertNotNull(result)
        assertEquals(42, result!!.taskId)
        assertEquals(1, result.windowingMode)
        assertEquals(0, result.left)
        assertEquals(0, result.top)
        assertEquals(960, result.right)
        assertEquals(600, result.bottom)
    }

    /** TC-13: getTaskState returns SplitTaskState with taskId -1 when no task is running (status 0). */
    @Test
    fun `getTaskState returns SplitTaskState with taskId -1 when no task running`() = runBlocking {
        val client = clientWith(taskStateFake(0, -1, 0, 0, 0, 0, 0))
        val result = client.getTaskState("com.example.app")
        assertNotNull(result)
        assertEquals(-1, result!!.taskId)
        assertEquals(0, result.windowingMode)
    }

    /** TC-14: getTaskState returns null when daemon replies with non-zero status. */
    @Test
    fun `getTaskState returns null on non-zero status`() = runBlocking {
        val client = clientWith(taskStateFake(-1, 0, 0, 0, 0, 0, 0))
        assertNull(client.getTaskState("com.example.app"))
    }

    /** TC-15: getTaskState returns null when binder is null (daemon unreachable). */
    @Test
    fun `getTaskState returns null when binder is null`() = runBlocking {
        assertNull(clientWith(null).getTaskState("com.example.app"))
    }

    // ---------------------------------------------------------------------------
    // TX_APP_SPLIT_SUPPORTED tests (TC-16 to TC-19)
    // ---------------------------------------------------------------------------

    /** Writes a TX_APP_SPLIT_SUPPORTED reply: status + supported. */
    private fun splitSupportedFake(status: Int, supported: Int): IBinder =
        object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeInt(status)
                reply.writeInt(supported)
                reply.setDataPosition(0)
                return true
            }
        }

    /** TC-16: isAppSplitSupported returns true when supported == 1. */
    @Test
    fun `isAppSplitSupported returns true when supported is 1`() = runBlocking {
        val result = clientWith(splitSupportedFake(0, 1)).isAppSplitSupported("com.example.nav")
        assertEquals(true, result)
    }

    /** TC-17: isAppSplitSupported returns false when supported == 0. */
    @Test
    fun `isAppSplitSupported returns false when supported is 0`() = runBlocking {
        val result = clientWith(splitSupportedFake(0, 0)).isAppSplitSupported("com.example.nav")
        assertEquals(false, result)
    }

    /** TC-18: isAppSplitSupported returns null when status is non-zero (exception in daemon). */
    @Test
    fun `isAppSplitSupported returns null on non-zero status`() = runBlocking {
        assertNull(clientWith(splitSupportedFake(-1, 0)).isAppSplitSupported("com.example.nav"))
    }

    /** TC-19: isAppSplitSupported returns null when binder is null (daemon unreachable). */
    @Test
    fun `isAppSplitSupported returns null when binder is null`() = runBlocking {
        assertNull(clientWith(null).isAppSplitSupported("com.example.nav"))
    }

    /** TC-20: isAppSplitSupported returns null when transact returns false (BYD extension absent on this ROM). */
    @Test
    fun `isAppSplitSupported returns null when transact is rejected`() = runBlocking {
        assertNull(clientWith(rejectingFake).isAppSplitSupported("com.example.nav"))
    }

    /** TC-21: getTaskState returns null when status-0 reply is truncated (fewer than 6 data ints). */
    @Test
    fun `getTaskState returns null on truncated status-0 reply`() = runBlocking {
        val truncatedFake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                // status=0 but only taskId follows — 5 missing ints (windowingMode, l, t, r, b).
                reply!!.writeInt(0)   // status ok
                reply.writeInt(42)    // taskId (only one extra int, not the required 6)
                reply.setDataPosition(0)
                return true
            }
        }
        assertNull(clientWith(truncatedFake).getTaskState("com.example.app"))
    }

    // ---------------------------------------------------------------------------
    // TX_GET_VERSION tests (TC-22 to TC-24)
    // ---------------------------------------------------------------------------

    /** TC-22: daemonVersion() returns the versionCode when daemon replies status=0. */
    @Test
    fun `daemonVersion returns version code when status is 0`() = runBlocking {
        val client = clientWith(liveFake(status = 0, value = 384))
        assertEquals(384L, client.daemonVersion())
    }

    /** TC-23: daemonVersion() returns null when transact returns false (old daemon, TX unknown). */
    @Test
    fun `daemonVersion returns null when transact is rejected`() = runBlocking {
        assertNull(clientWith(rejectingFake).daemonVersion())
    }

    /** TC-24: daemonVersion() returns null when binder is null (daemon not running). */
    @Test
    fun `daemonVersion returns null when binder is null`() = runBlocking {
        assertNull(clientWith(null).daemonVersion())
    }

    // ---------------------------------------------------------------------------
    // TX_GET_TOP_PACKAGE tests (TC-25 to TC-27)
    // ---------------------------------------------------------------------------

    /** Builds a fake binder that writes [status] then the String [pkg] to the reply parcel. */
    private fun liveFakeStringReply(status: Int, pkg: String): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply!!.writeInt(status)
            reply.writeString(pkg)
            reply.setDataPosition(0)
            return true
        }
    }

    /** TC-25: getTopTaskPackage() returns the package name when daemon replies status=0. */
    @Test
    fun `getTopTaskPackage returns package name when status is 0`() = runBlocking {
        val client = clientWith(liveFakeStringReply(status = 0, pkg = "com.byd.mediacenter"))
        assertEquals("com.byd.mediacenter", client.getTopTaskPackage())
    }

    /** TC-26: getTopTaskPackage() returns null when transact returns false (old daemon without TX_GET_TOP_PACKAGE). */
    @Test
    fun `getTopTaskPackage returns null when transact is rejected`() = runBlocking {
        assertNull(clientWith(rejectingFake).getTopTaskPackage())
    }

    /** TC-27: getTopTaskPackage() returns null when binder is null (daemon not running). */
    @Test
    fun `getTopTaskPackage returns null when binder is null`() = runBlocking {
        assertNull(clientWith(null).getTopTaskPackage())
    }

    // ---------------------------------------------------------------------------
    // TX_RAISE_FREEFORM_TASK tests (TC-28 to TC-30)
    // ---------------------------------------------------------------------------

    /** TC-28: raiseFreeformTask returns true when daemon replies status=0 (raise succeeded). */
    @Test
    fun `raiseFreeformTask returns true when status is 0`() = runBlocking {
        val client = clientWith(liveFake(status = 0))
        assertTrue(client.raiseFreeformTask(
            "com.example.navi", displayId = 0, activityType = HelperBinderProtocol.PANE_TYPE_RECENTS))
    }

    /** TC-29: raiseFreeformTask returns false when daemon replies status=-1 (raise failed / component not found). */
    @Test
    fun `raiseFreeformTask returns false when status is negative`() = runBlocking {
        val client = clientWith(liveFake(status = -1))
        assertFalse(client.raiseFreeformTask(
            "com.example.navi", displayId = 0, activityType = HelperBinderProtocol.PANE_TYPE_RECENTS))
    }

    /**
     * TC-30: raiseFreeformTask returns false when transact is rejected (old daemon without
     * TX_RAISE_FREEFORM_TASK). This is the signal for reAssertSplitZOrder to fall back to
     * setFocusedTask (386-era behavior).
     */
    @Test
    fun `raiseFreeformTask returns false when transact is rejected (old daemon fallback path)`() = runBlocking {
        assertFalse(clientWith(rejectingFake).raiseFreeformTask(
            "com.example.navi", displayId = 0, activityType = HelperBinderProtocol.PANE_TYPE_RECENTS))
    }

    /** TC-30a: the pane activityType is the trailing int, written after displayId. */
    @Test
    fun `raiseFreeformTask marshals activityType after displayId`() = runBlocking {
        var pkg: String? = null; var displayId = -1; var activityType = -1
        clientWith(capturingFake {
            pkg = it.readString(); displayId = it.readInt(); activityType = it.readInt()
        }).raiseFreeformTask("com.example.navi", displayId = 0,
            activityType = HelperBinderProtocol.PANE_TYPE_STANDARD)
        assertEquals("com.example.navi", pkg)
        assertEquals(0, displayId)
        assertEquals(HelperBinderProtocol.PANE_TYPE_STANDARD, activityType)
    }

    /** TC-30b: setTaskWindowingMode writes the requested activityType after windowingMode. */
    @Test
    fun `setTaskWindowingMode marshals activityType after windowingMode`() = runBlocking {
        val args = IntArray(3)
        clientWith(capturingFake { for (i in 0..2) args[i] = it.readInt() })
            .setTaskWindowingMode(5, 5, HelperBinderProtocol.PANE_TYPE_STANDARD)
        assertEquals(5, args[0]); assertEquals(5, args[1])
        assertEquals(HelperBinderProtocol.PANE_TYPE_STANDARD, args[2])
    }

    /** TC-30c: without an explicit activityType the fullscreen direction keeps legacy RECENTS. */
    @Test
    fun `setTaskWindowingMode defaults activityType to RECENTS`() = runBlocking {
        val args = IntArray(3)
        clientWith(capturingFake { for (i in 0..2) args[i] = it.readInt() })
            .setTaskWindowingMode(5, 1)
        assertEquals(5, args[0]); assertEquals(1, args[1])
        assertEquals(HelperBinderProtocol.PANE_TYPE_RECENTS, args[2])
    }

    // ---------------------------------------------------------------------------
    // TX_DUMP_FIDS tests (TC-31 to TC-36) — chunked transport (Q4 / F-8)
    // ---------------------------------------------------------------------------

    /**
     * Fake binder that serves [fullDump] in chunks of [chunkMaxBytes] bytes via the chunked
     * TX_DUMP_FIDS protocol (Q4): reads offset from data, writes status + totalLength + chunk.
     * The stateful [AtomicInteger] tracks the current byte position; each transact() call
     * advances it by the served chunk size.  This correctly simulates a daemon that caches
     * the dump and slices it on each client call.
     */
    private fun chunkedDumpFake(
        fullDump: String,
        chunkMaxBytes: Int = HelperBinderProtocol.DUMP_CHUNK_MAX,
    ): IBinder {
        val dumpBytes = fullDump.toByteArray(Charsets.UTF_8)
        val position = AtomicInteger(0)
        return object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                val pos = position.get()
                val end = minOf(pos + chunkMaxBytes, dumpBytes.size)
                val chunk = if (pos < dumpBytes.size) dumpBytes.copyOfRange(pos, end) else ByteArray(0)
                position.addAndGet(chunk.size)
                reply!!.writeInt(0)
                reply.writeInt(dumpBytes.size)
                reply.writeByteArray(chunk)
                reply.setDataPosition(0)
                return true
            }
        }
    }

    /**
     * TC-31: dumpFids assembles a single-chunk reply and returns DumpFidsResult.Success.
     * The dump fits in one chunk (< CHUNK_MAX), so the loop exits after one iteration.
     */
    @Test
    fun `dumpFids returns Success when daemon replies status 0 single chunk`() = runBlocking {
        val expected = "BYDAutoFeatureIds.FID_SOC=1246777400"
        val client = clientWith(chunkedDumpFake(expected))
        val result = client.dumpFids()
        assertTrue("must be Success", result is DumpFidsResult.Success)
        assertEquals(expected, (result as DumpFidsResult.Success).dump)
    }

    /**
     * TC-32: dumpFids returns DumpFidsResult.ReadError when daemon replies status=-1 on
     * the first chunk (reflection failed on daemon side).
     *
     * The daemon error branch writes the full protocol reply (status=-1, totalLength=0, empty chunk)
     * so the client reads status and returns ReadError — NOT BinderAbsent. Before Q4-1 fix the
     * daemon wrote only writeInt(-1); the client saw dataAvail()<8, returned null, and mapped to
     * BinderAbsent ("демон недоступен") — the exact lie F-9 was written to eliminate.
     */
    @Test
    fun `dumpFids returns ReadError when daemon replies status negative`() = runBlocking {
        val errorFake: IBinder = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                // Full protocol reply matching the fixed daemon error branch (Q4-1):
                // status=-1, totalLength=0, empty byte[] — client reads all 8+ bytes and returns ReadError.
                reply!!.writeInt(-1); reply.writeInt(0); reply.writeByteArray(ByteArray(0))
                reply.setDataPosition(0)
                return true
            }
        }
        val result = clientWith(errorFake).dumpFids()
        assertTrue("full-reply status=-1 must produce ReadError, not BinderAbsent", result is DumpFidsResult.ReadError)
    }

    /**
     * TC-33: dumpFids returns DumpFidsResult.BinderAbsent when transact is rejected (old daemon
     * without TX_DUMP_FIDS). The feature auto-disarms without crashing.
     */
    @Test
    fun `dumpFids returns BinderAbsent when transact is rejected (old daemon)`() = runBlocking {
        val result = clientWith(rejectingFake).dumpFids()
        assertTrue("rejected transact on first chunk must be BinderAbsent",
            result is DumpFidsResult.BinderAbsent)
    }

    /**
     * TC-34: dumpFids assembles a multi-chunk reply correctly.
     * The fake serves the dump in tiny 4-byte chunks so the loop must execute many iterations.
     * Anti-vacuity: replacing the loop with a single transactParsed call would yield only 4
     * bytes and the assertion on the full string would fail.
     */
    @Test
    fun `dumpFids assembles multi-chunk reply into full string`() = runBlocking {
        val payload = "BYDAutoFeatureIds.FID_SOC=1246777400\n" +
            "BYDAutoConstants.SOME_CONST=42\n" +
            "BYDAutoFeatureIds.FID_ANOTHER=999"
        // Tiny chunk max (4 bytes) forces many loop iterations.
        val client = clientWith(chunkedDumpFake(payload, chunkMaxBytes = 4))
        val result = client.dumpFids()
        assertTrue("multi-chunk assembly must be Success", result is DumpFidsResult.Success)
        assertEquals("assembled dump must equal original payload",
            payload, (result as DumpFidsResult.Success).dump)
    }

    /**
     * TC-35: dumpFids returns DumpFidsResult.ReadError when transact fails mid-loop
     * (after the first chunk succeeds). Must not return BinderAbsent (which would be a lie —
     * the binder was alive for at least one chunk).
     */
    @Test
    fun `dumpFids returns ReadError when transact fails mid-loop`() = runBlocking {
        val firstChunkPayload = "FID_SOC=1246777400"
        val payloadBytes = firstChunkPayload.toByteArray(Charsets.UTF_8)
        val callCount = AtomicInteger(0)
        val midLoopFailFake: IBinder = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                val call = callCount.getAndIncrement()
                if (call == 0) {
                    // First chunk — succeeds, but lies about totalLength (says more data to come).
                    reply!!.writeInt(0)
                    reply.writeInt(payloadBytes.size + 10)   // totalLength > chunk.size → loop continues
                    reply.writeByteArray(payloadBytes)
                    reply.setDataPosition(0)
                    return true
                }
                // Second call — simulate binder failure.
                return false
            }
        }
        val result = clientWith(midLoopFailFake).dumpFids()
        assertTrue(
            "mid-loop failure must be ReadError (not BinderAbsent — binder was alive for chunk 0)",
            result is DumpFidsResult.ReadError,
        )
    }

    /**
     * TC-36: dumpFids returns DumpFidsResult.ReadError when the daemon signals status != 0
     * on a chunk after the first (partial-read error, not daemon-absent).
     */
    @Test
    fun `dumpFids returns ReadError when second chunk has non-zero status`() = runBlocking {
        val firstChunkBytes = "PARTIAL=1".toByteArray(Charsets.UTF_8)
        val callCount = AtomicInteger(0)
        val secondChunkErrorFake: IBinder = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                val call = callCount.getAndIncrement()
                reply!!
                if (call == 0) {
                    // Chunk 0 OK — advertise more data.
                    reply.writeInt(0)
                    reply.writeInt(firstChunkBytes.size + 20)
                    reply.writeByteArray(firstChunkBytes)
                } else {
                    // Chunk 1 — daemon returns error status.
                    reply.writeInt(-1)
                    reply.writeInt(0)
                    reply.writeByteArray(ByteArray(0))
                }
                reply.setDataPosition(0)
                return true
            }
        }
        val result = clientWith(secondChunkErrorFake).dumpFids()
        assertTrue(
            "non-zero status on second chunk must be ReadError",
            result is DumpFidsResult.ReadError,
        )
    }

    /**
     * TC-37: dumpFids does not produce BinderAbsent when a pre-Q4 old-format reply is received
     * (C-3). An old daemon that accepted TX_DUMP_FIDS would write writeInt(status) +
     * writeString(content) — no totalLength int, no byte array. The new client reads status OK
     * then misinterprets the string length field as totalLength; createByteArray returns null
     * (oversized length from raw UTF-16 bytes); the loop guard eventually fires after
     * MAX_DUMP_CHUNKS iterations and returns ReadError — NOT BinderAbsent, because transact
     * returned true (the daemon was alive).
     *
     * The real guard against this scenario in production is ensureRunning() called before dumpFids
     * in SettingsViewModel (C-3a), which kills any old-format daemon before the TX is attempted.
     */
    @Test
    fun `dumpFids does not return BinderAbsent for pre-Q4 old-format reply (C-3)`() = runBlocking {
        val oldFormatFake: IBinder = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                // Pre-Q4 TX_DUMP_FIDS format: status (int) + content (writeString, UTF-16).
                // No totalLength field, no byte array — incompatible with the new chunked reader.
                reply!!.writeInt(0)
                reply.writeString("BYDAutoFeatureIds.FID_SOC=1246777400")
                reply.setDataPosition(0)
                return true  // transact accepted — daemon was alive
            }
        }
        val result = clientWith(oldFormatFake).dumpFids()
        // Transact returned true → chunkIndex advances → never takes the BinderAbsent branch.
        // Result is ReadError (loop guard after MAX_DUMP_CHUNKS iterations).
        assertFalse(
            "old-format reply from a live daemon must NOT map to BinderAbsent",
            result is DumpFidsResult.BinderAbsent,
        )
    }

    // TX_GET_TOP_TASK tests (C-4)

    /**
     * TC-38: getTopTask() returns null when the daemon replies status=-1 even when the rest of
     * the payload would parse as a valid TopTaskInfo (C-4). This is the fail-safe path used when
     * displayId is absent via reflection — topTaskInfo() returns null, the daemon writes status=-1
     * with a full payload. The client must honour status and return null, not a synthesised
     * TopTaskInfo with displayId=0 that would falsely trigger COVERED teardown.
     *
     * Anti-vacuity: removing `if (status != 0) return@transactParsed null` from getTopTask()
     * makes the client parse the pkg/taskId/windowingMode/activityType/displayId fields that
     * follow and return a non-null TopTaskInfo — making this assertNull fail.
     */
    @Test
    fun `getTopTask returns null when daemon replies status minus 1 full payload (C-4)`() = runBlocking {
        val failStatusWithPayloadFake: IBinder = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                // Daemon sends status=-1 (no main-display task) followed by the fields that
                // would be a valid TopTaskInfo if status were 0. Without the status check the
                // client would parse these and return non-null.
                reply!!.writeInt(-1)                          // status: failure
                reply.writeString("com.example.phantom")     // pkg (would be parsed if status ignored)
                reply.writeInt(99)                            // taskId
                reply.writeInt(1 /* FULLSCREEN */)           // windowingMode
                reply.writeInt(1 /* STANDARD */)             // activityType
                reply.writeInt(0)                             // displayId=0 (the synthesised main-display)
                reply.setDataPosition(0)
                return true
            }
        }
        assertNull(
            "getTopTask must return null for status=-1 even when pkg/taskId/displayId follow in payload",
            clientWith(failStatusWithPayloadFake).getTopTask(),
        )
    }

    /**
     * TC-40: getTopTask() carries the trailing visibility flag of the daemon, and every answer
     * that is not a plain 1/0 leaves it unknown: a daemon that predates the field writes nothing
     * after displayId, and a ROM without RunningTaskInfo.isVisible makes the daemon write -1.
     * The split adoption acts on `true` only, so "unknown" must never arrive as visible.
     *
     * Anti-vacuity: parsing the trailing int unconditionally makes the absent case read past the
     * end of the parcel; mapping -1 to `!= 0` makes the third assertion fail with `true`.
     */
    @Test
    fun `getTopTask reads the trailing visible flag and tolerates its absence`() = runBlocking {
        fun topTaskFake(trailing: Int?): IBinder = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeInt(0)                 // status
                reply.writeString("com.example.top")
                reply.writeInt(99)                  // taskId
                reply.writeInt(1 /* FULLSCREEN */)  // windowingMode
                reply.writeInt(1 /* STANDARD */)    // activityType
                reply.writeInt(0)                   // displayId
                if (trailing != null) reply.writeInt(trailing)
                reply.setDataPosition(0)
                return true
            }
        }
        assertEquals(true, clientWith(topTaskFake(1)).getTopTask()?.visible)
        assertEquals(false, clientWith(topTaskFake(0)).getTopTask()?.visible)
        assertNull(
            "a ROM without isVisible answers -1 - not a visible task",
            clientWith(topTaskFake(-1)).getTopTask()?.visible,
        )
        assertNull(
            "a daemon that predates the field writes no flag at all",
            clientWith(topTaskFake(null)).getTopTask()?.visible,
        )
        // The fields before the flag are read exactly as they were.
        assertEquals(99, clientWith(topTaskFake(null)).getTopTask()?.taskId)
    }

    // dumpFids null-bytes guard (C-3-R1)

    /**
     * TC-39: dumpFids returns ReadError (not Success("")) when the daemon writes status=0 with a
     * null byte array (C-3-R1). Without the null-bytes guard, totalLength=0 + null bytes causes
     * `offset >= totalLength` (0 >= 0) to break immediately, returning Success("") — a false
     * positive empty dump. The guard catches this and returns ReadError before the break.
     *
     * Anti-vacuity: removing the `if (chunkReply.bytes == null) return DumpFidsResult.ReadError(...)`
     * guard causes the test to receive Success("") instead of ReadError, making assertTrue(is
     * ReadError) fail.
     */
    @Test
    fun `dumpFids returns ReadError when daemon replies status 0 with null byte array (C-3-R1)`() = runBlocking {
        val nullBytesFake: IBinder = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                // status=0 (OK), totalLength=0, explicitly null byte array.
                // writeByteArray(null) → createByteArray() returns null (null indicator in Parcel).
                // Without the null-bytes guard, totalLength=0 + bytes=ByteArray(0) would break the
                // loop immediately and return Success("") — a false positive empty dump.
                reply!!.writeInt(0)       // status=0
                reply.writeInt(0)         // totalLength=0
                reply.writeByteArray(null) // null byte array → createByteArray() returns null
                reply.setDataPosition(0)
                return true
            }
        }
        val result = clientWith(nullBytesFake).dumpFids()
        assertTrue(
            "status=0 with null byte array must return ReadError, not Success (C-3-R1)",
            result is DumpFidsResult.ReadError,
        )
    }
}
