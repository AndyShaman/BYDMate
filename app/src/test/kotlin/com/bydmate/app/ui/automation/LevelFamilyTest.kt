package com.bydmate.app.ui.automation

import com.bydmate.app.data.vehicle.AdaptiveSeatChannel
import com.bydmate.app.data.vehicle.CommandTranslator
import com.bydmate.app.data.vehicle.SeatChannel
import com.bydmate.app.data.vehicle.SeatChannelStore
import com.bydmate.app.data.vehicle.SeatCommand
import com.bydmate.app.data.vehicle.SeatGroup
import com.bydmate.app.data.vehicle.SeatWriter
import com.bydmate.app.data.vehicle.WriteAllowlist
import com.bydmate.app.data.vehicle.WriteOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** One level row per graded car action: the stored command strings stay exactly the old ones. */
class LevelFamilyTest {

    private fun LevelFamily.grid(): List<Int> = (range.first..range.last step step).toList()

    @Test fun `every family round trips at min, max and default`() {
        for (f in LevelFamily.entries) {
            for (v in listOf(f.range.first, f.range.last, f.default)) {
                assertEquals("$f $v", LevelValue(f, v), LevelFamily.of(f.command(v)))
            }
        }
    }

    @Test fun `every value on the grid round trips`() {
        for (f in LevelFamily.entries) for (v in f.grid()) {
            assertEquals("$f $v", LevelValue(f, v), LevelFamily.of(f.command(v)))
        }
    }

    @Test fun `families and ranges as approved`() {
        val expected = mapOf(
            LevelFamily.TEMPERATURE to listOf(16, 33, 1, 22),
            LevelFamily.FAN to listOf(1, 7, 1, 3),
            LevelFamily.SEAT_HEAT_DRIVER to listOf(0, 5, 1, 3),
            LevelFamily.SEAT_HEAT_PASSENGER to listOf(0, 5, 1, 3),
            LevelFamily.SEAT_VENT_DRIVER to listOf(0, 5, 1, 3),
            LevelFamily.SEAT_VENT_PASSENGER to listOf(0, 5, 1, 3),
            LevelFamily.WINDOW_DRIVER to listOf(0, 100, 10, 50),
            LevelFamily.WINDOW_PASSENGER to listOf(0, 100, 10, 50),
            LevelFamily.WINDOW_REAR_LEFT to listOf(0, 100, 10, 50),
            LevelFamily.WINDOW_REAR_RIGHT to listOf(0, 100, 10, 50),
            LevelFamily.FRIDGE_COOL to listOf(-6, 6, 1, 0),
            LevelFamily.FRIDGE_HEAT to listOf(35, 50, 1, 40),
        )
        assertEquals(expected.keys, LevelFamily.entries.toSet())
        for ((f, e) in expected) assertEquals("$f", e, listOf(f.range.first, f.range.last, f.step, f.default))
    }

    @Test fun `commands keep the old shape`() {
        assertEquals("设置温度22", LevelFamily.TEMPERATURE.command(22))
        assertEquals("风量7", LevelFamily.FAN.command(7))
        assertEquals("主驾座椅加热3档", LevelFamily.SEAT_HEAT_DRIVER.command(3))
        assertEquals("主驾座椅加热关闭", LevelFamily.SEAT_HEAT_DRIVER.command(0))
        assertEquals("副驾座椅加热关闭", LevelFamily.SEAT_HEAT_PASSENGER.command(0))
        assertEquals("主驾座椅通风关闭", LevelFamily.SEAT_VENT_DRIVER.command(0))
        assertEquals("副驾座椅通风5档", LevelFamily.SEAT_VENT_PASSENGER.command(5))
        assertEquals("主驾打开30", LevelFamily.WINDOW_DRIVER.command(30))
        assertEquals("副驾打开100", LevelFamily.WINDOW_PASSENGER.command(100))
        assertEquals("后左打开0", LevelFamily.WINDOW_REAR_LEFT.command(0))
        assertEquals("后右打开70", LevelFamily.WINDOW_REAR_RIGHT.command(70))
        assertEquals("冰箱制冷-3度", LevelFamily.FRIDGE_COOL.command(-3))
        assertEquals("冰箱制热45度", LevelFamily.FRIDGE_HEAT.command(45))
    }

    @Test fun `old rules open as their family and value`() {
        assertEquals(LevelValue(LevelFamily.TEMPERATURE, 22), LevelFamily.of("设置温度22"))
        assertEquals(LevelValue(LevelFamily.SEAT_HEAT_DRIVER, 0), LevelFamily.of("主驾座椅加热关闭"))
        assertEquals(LevelValue(LevelFamily.SEAT_VENT_PASSENGER, 2), LevelFamily.of("副驾座椅通风2档"))
        assertEquals(LevelValue(LevelFamily.FRIDGE_COOL, -3), LevelFamily.of("冰箱制冷-3度"))
        assertEquals(LevelValue(LevelFamily.FRIDGE_HEAT, 50), LevelFamily.of("冰箱制热50度"))
        assertEquals(LevelValue(LevelFamily.WINDOW_DRIVER, 100), LevelFamily.of("主驾打开100"))
        assertEquals(LevelValue(LevelFamily.WINDOW_REAR_RIGHT, 0), LevelFamily.of("后右打开0"))
        assertEquals(LevelValue(LevelFamily.FAN, 1), LevelFamily.of("风量1"))
    }

    @Test fun `other commands are not a level`() {
        for (cmd in listOf(
            "车窗关闭", "主驾半开", "主驾通风", "冰箱制冷", "冰箱制热", "冰箱关闭", "自动空调", "天窗打开50",
            "设置温度35", "设置温度", "风量0", "主驾座椅加热0档", "主驾座椅加热6档", "主驾打开101", "冰箱制冷-7度", "",
        )) assertNull(cmd, LevelFamily.of(cmd))
    }

    // Leading zeros are no command the car path knows: resolveSeat takes one digit, and
    // «0100» would go through the percent fid instead of the dedicated open.
    @Test fun `only canonical digits are a level`() {
        for (cmd in listOf("主驾座椅加热03档", "主驾打开0100", "主驾打开030", "设置温度022", "风量03", "冰箱制冷-03度", "冰箱制热040度")) {
            assertNull(cmd, LevelFamily.of(cmd))
        }
    }

    @Test fun `step buttons walk the grid and stop at the ends`() {
        assertEquals(40, levelStep(50, 0..100, 10, up = false))
        assertEquals(60, levelStep(50, 0..100, 10, up = true))
        assertEquals(0, levelStep(0, 0..100, 10, up = false))
        assertEquals(100, levelStep(100, 0..100, 10, up = true))
        // A value off the grid goes to its neighbour on the grid.
        assertEquals(30, levelStep(35, 0..100, 10, up = false))
        assertEquals(40, levelStep(35, 0..100, 10, up = true))
        assertEquals(-4, levelStep(-3, -6..6, 1, up = false))
        assertEquals(33, levelStep(33, 16..33, 1, up = true))
    }

    @Test fun `slider snaps to the grid`() {
        assertEquals(30, levelSnap(31.2f, 0..100, 10))
        assertEquals(40, levelSnap(36f, 0..100, 10))
        assertEquals(-6, levelSnap(-6.4f, -6..6, 1))
        assertEquals(22, levelSnap(21.6f, 16..33, 1))
    }

    @Test fun `every picker anchor is a catalog entry of the same category`() {
        for (f in LevelFamily.entries) {
            val before = f.pickerBefore ?: continue
            assertTrue(
                "$f before $before",
                ACTION_COMMANDS.any { (it.toggleTarget ?: it.command) == before && it.categoryRes == f.categoryRes },
            )
        }
    }

    @Test fun `the graded tiles are gone from the catalog`() {
        val commands = ACTION_COMMANDS.map { it.command }.toSet()
        for (gone in listOf("设置温度18", "设置温度25", "风量3", "主驾座椅加热1档", "副驾座椅通风关闭", "冰箱制冷0度", "冰箱制热50度")) {
            assertTrue(gone, gone !in commands)
        }
        // Open, close, vent, half and fridge mode tiles stay.
        for (kept in listOf("主驾打开100", "主驾打开0", "主驾通风", "主驾半开", "冰箱制冷", "冰箱制热", "冰箱关闭")) {
            assertTrue(kept, kept in commands)
        }
    }

    // --- Every value a row can store reaches the car inside the production allowlist ---

    private val allowlist: WriteAllowlist by lazy {
        val root = generateSequence(File(".").canonicalFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/assets/competitor-actions.json").exists() }
            ?: error("Cannot find app/src/main/assets/competitor-actions.json from ${File(".").canonicalPath}")
        WriteAllowlist.loadProduction { File(root, "app/src/main/assets/competitor-actions.json").readText() }
    }

    private val seatGroups = mapOf(
        LevelFamily.SEAT_HEAT_DRIVER to SeatGroup.DRIVER_HEAT,
        LevelFamily.SEAT_HEAT_PASSENGER to SeatGroup.PASSENGER_HEAT,
        LevelFamily.SEAT_VENT_DRIVER to SeatGroup.DRIVER_VENT,
        LevelFamily.SEAT_VENT_PASSENGER to SeatGroup.PASSENGER_VENT,
    )

    @Test fun `every non-seat value resolves inside its allowlist range`() {
        for (f in LevelFamily.entries.filter { it !in seatGroups }) for (v in f.grid()) {
            val cmd = f.command(v)
            val resolved = CommandTranslator.resolve(cmd)
            assertTrue("$cmd resolves to nothing", resolved.isNotEmpty())
            for (r in resolved) {
                val entry = allowlist.find(r.actionName)
                assertTrue("$cmd → ${r.actionName} not in allowlist", entry != null)
                assertTrue(
                    "$cmd → ${r.actionName}=${r.value} outside [${entry!!.valueMin}..${entry.valueMax}]",
                    r.value in entry.valueMin..entry.valueMax,
                )
            }
        }
    }

    private class Store(private var w: SeatChannel) : SeatChannelStore {
        override fun winner() = w
        override fun setWinner(channel: SeatChannel) { w = channel }
        override fun reprobeExhausted() = false
        override fun claimReprobe() = true
    }

    /** Every write the seat channel makes for [cmd], from a fresh car whose winner is [winner]. */
    private fun seatWrites(cmd: String, winner: SeatChannel, primaryDead: Boolean): List<Pair<String, Int>> {
        val seat = CommandTranslator.resolveSeat(cmd)!!
        val writes = mutableListOf<Pair<String, Int>>()
        val writer = SeatWriter { name, value ->
            writes += name to value
            if (primaryDead && !name.endsWith("_fallback")) WriteOutcome.NOOP else WriteOutcome.REAL
        }
        runTest { AdaptiveSeatChannel(writer, Store(winner)).actuate(seat.group, seat.level) }
        return writes
    }

    // Seat commands go to the seat channel (VehicleApiImpl tries resolveSeat before resolve):
    // off (0, «…关闭») is the switch write, 1..5 the switch plus the level, or the fallback stage.
    @Test fun `every seat value reaches the seat channel inside its allowlist range`() {
        val paths = listOf(SeatChannel.PRIMARY to false, SeatChannel.FALLBACK to false, SeatChannel.UNKNOWN to true)
        for ((f, group) in seatGroups) for (v in f.grid()) {
            val cmd = f.command(v)
            assertEquals(cmd, SeatCommand(group, v), CommandTranslator.resolveSeat(cmd))
            for ((winner, primaryDead) in paths) assertAllowed("$cmd via $winner", seatWrites(cmd, winner, primaryDead))
        }
    }

    private fun assertAllowed(what: String, writes: List<Pair<String, Int>>) {
        assertTrue("$what wrote nothing", writes.isNotEmpty())
        for ((name, value) in writes) {
            val entry = allowlist.find(name)
            assertTrue("$what → $name not in allowlist", entry != null)
            assertTrue("$what → $name=$value outside [${entry!!.valueMin}..${entry.valueMax}]", value in entry.valueMin..entry.valueMax)
        }
    }
}
