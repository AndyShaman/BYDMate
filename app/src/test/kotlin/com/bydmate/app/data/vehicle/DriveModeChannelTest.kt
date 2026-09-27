package com.bydmate.app.data.vehicle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Support flag, flotation refusal, "already there" and the target readback of a mode switch. */
class DriveModeChannelTest {
    private val target = WriteAllowlist.DRIVE_MODE_TARGET_FID
    private val writes = mutableListOf<Pair<String, Int>>()
    private val flags = mutableMapOf<Int, Int?>()
    private val targets = ArrayDeque<Int?>()
    private var lastTarget: Int? = null
    private var status = WriteOutcome.REAL
    private var kmh: Int? = 0
    private var speedCalls = 0
    private val reads = mutableListOf<Int>()

    // beforeSend is called the way VehicleApiImpl.doWriteOutcome calls it: right before the
    // "helper" write, so a false return skips the write and reports TRANSIENT.
    private val channel = DriveModeChannel(
        DriveModeWriter { name, value, beforeSend ->
            if (!beforeSend()) WriteOutcome.TRANSIENT else { writes += name to value; status }
        },
        DriveModeReader { fid ->
            reads += fid
            if (fid == target) (targets.removeFirstOrNull() ?: lastTarget).also { lastTarget = it }
            else flags.getOrDefault(fid, 0)
        },
        speed = { speedCalls++; kmh },
    )

    private fun targets(vararg values: Int?) = targets.addAll(values.toList())

    @Test fun `eco writes value 2 and succeeds once the target follows`() = runTest {
        targets(1, 1, 2)
        val out = channel.actuate(DriveMode.ECO)
        assertEquals(DriveModeChannel.Result.OK, out.result)
        assertEquals("OK", out.verdict)
        assertEquals(listOf("drive_mode_eco" to 2), writes)
    }

    @Test fun `each mode writes its own value`() = runTest {
        val expected = mapOf(
            DriveMode.NORMAL to 1, DriveMode.ECO to 2, DriveMode.SPORT to 3, DriveMode.SNOW to 4,
            DriveMode.SAND to 5, DriveMode.MUD to 6, DriveMode.MOUNTAIN to 7, DriveMode.ROCK to 8,
            DriveMode.SMART to 21,
        )
        for ((mode, value) in expected) {
            writes.clear(); targets.clear(); lastTarget = null
            targets(if (value == 1) 2 else 1, value)
            assertEquals(mode.name, DriveModeChannel.Result.OK, channel.actuate(mode).result)
            assertEquals(listOf(mode.actionName to value), writes)
        }
    }

    @Test fun `a target that never follows is not changed`() = runTest {
        targets(1)
        val out = channel.actuate(DriveMode.SPORT)
        assertEquals(DriveModeChannel.Result.NOT_CHANGED, out.result)
        assertEquals(1, out.target)
        assertEquals(1, writes.size)
    }

    @Test fun `an unsupported mode is refused before any write`() = runTest {
        flags[DriveMode.ROCK.supportFid] = 1
        targets(1)
        assertEquals(DriveModeChannel.Result.NOT_SUPPORTED, channel.actuate(DriveMode.ROCK).result)
        assertTrue(writes.isEmpty())
    }

    @Test fun `a sentinel support flag is unreadable, not unsupported`() = runTest {
        flags[DriveMode.SNOW.supportFid] = -10011
        targets(1)
        assertEquals(DriveModeChannel.Result.UNREADABLE, channel.actuate(DriveMode.SNOW).result)
        assertTrue(writes.isEmpty())
    }

    @Test fun `an unreadable support flag writes nothing`() = runTest {
        flags[DriveMode.ECO.supportFid] = null
        targets(1)
        assertEquals(DriveModeChannel.Result.UNREADABLE, channel.actuate(DriveMode.ECO).result)
        assertTrue(writes.isEmpty())
    }

    @Test fun `flotation refuses any change`() = runTest {
        targets(DriveMode.TARGET_FLOTATION)
        val out = channel.actuate(DriveMode.NORMAL)
        assertEquals(DriveModeChannel.Result.FLOTATION, out.result)
        assertTrue(writes.isEmpty())
    }

    @Test fun `an unreadable target writes nothing`() = runTest {
        targets(65535)
        assertEquals(DriveModeChannel.Result.UNREADABLE, channel.actuate(DriveMode.ECO).result)
        assertTrue(writes.isEmpty())
    }

    @Test fun `the requested mode already on succeeds without a write`() = runTest {
        targets(3)
        val out = channel.actuate(DriveMode.SPORT)
        assertEquals(DriveModeChannel.Result.OK, out.result)
        assertEquals("already", out.verdict)
        assertTrue(writes.isEmpty())
    }

    @Test fun `a write the daemon did not take is unreachable and not polled`() = runTest {
        status = WriteOutcome.TRANSIENT
        targets(1, 2)
        assertEquals(DriveModeChannel.Result.UNREACHABLE, channel.actuate(DriveMode.ECO).result)
        assertEquals(1, targets.size) // only the before-read happened
    }

    @Test fun `a terrain mode at 15 kmh proceeds`() = runTest {
        kmh = 15
        targets(1, 4)
        assertEquals(DriveModeChannel.Result.OK, channel.actuate(DriveMode.SNOW).result)
        assertEquals(listOf("drive_mode_snow" to 4), writes)
    }

    @Test fun `a terrain mode at 16 kmh is refused before any read or write`() = runTest {
        kmh = 16
        val out = channel.actuate(DriveMode.MUD)
        assertEquals(DriveModeChannel.Result.SPEED, out.result)
        assertEquals("too fast", out.verdict)
        assertEquals(16, out.speed)
        assertTrue(writes.isEmpty())
        assertTrue(reads.isEmpty())
    }

    @Test fun `a terrain mode at unknown speed is refused before any read or write`() = runTest {
        kmh = null
        val out = channel.actuate(DriveMode.SMART)
        assertEquals(DriveModeChannel.Result.SPEED, out.result)
        assertEquals("speed unknown", out.verdict)
        assertTrue(writes.isEmpty())
        assertTrue(reads.isEmpty())
    }

    @Test fun `eco at 120 kmh proceeds`() = runTest {
        kmh = 120
        targets(1, 2)
        assertEquals(DriveModeChannel.Result.OK, channel.actuate(DriveMode.ECO).result)
        assertEquals(listOf("drive_mode_eco" to 2), writes)
    }

    @Test fun `eco at unknown speed proceeds`() = runTest {
        kmh = null
        targets(1, 2)
        assertEquals(DriveModeChannel.Result.OK, channel.actuate(DriveMode.ECO).result)
    }

    @Test fun `speed rising between the first check and the guard is refused with no write and no poll`() = runTest {
        // 10 kmh passes the early check in run(); the target read below (one of the two
        // suspending reads before the guard) simulates the car speeding up while they run.
        var currentKmh: Int? = 10
        val localWrites = mutableListOf<Pair<String, Int>>()
        val localReads = mutableListOf<Int>()
        val localChannel = DriveModeChannel(
            DriveModeWriter { name, value, beforeSend ->
                if (!beforeSend()) WriteOutcome.TRANSIENT else { localWrites += name to value; WriteOutcome.REAL }
            },
            DriveModeReader { fid ->
                localReads += fid
                if (fid == target) { currentKmh = 20; 1 } else 0
            },
            speed = { currentKmh },
        )
        val out = localChannel.actuate(DriveMode.SNOW)
        assertEquals(DriveModeChannel.Result.SPEED, out.result)
        assertEquals("too fast", out.verdict)
        assertEquals(20, out.speed)
        assertTrue(localWrites.isEmpty())
        // support flag + before-target reads only: the guard's refusal skips the target poll.
        assertEquals(2, localReads.size)
    }

    @Test fun `eco does not read speed again inside the guard`() = runTest {
        kmh = 5
        targets(1, 2)
        speedCalls = 0
        channel.actuate(DriveMode.ECO)
        // one read: the early check in run(). The guard skips non-terrain modes without reading.
        assertEquals(1, speedCalls)
    }
}
