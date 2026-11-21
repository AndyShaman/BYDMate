package com.bydmate.app.data.tpms

import com.bydmate.app.data.backup.BackupPart
import com.bydmate.app.data.backup.BackupParts
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import com.bydmate.app.helper.dtc.TpmsPlanEntry
import com.bydmate.app.helper.dtc.TpmsWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TyreSetsTest {

    private val a = 0x11111111
    private val b = 0x22222222
    private val c = 0x33333333
    private val d = 0x44444444
    private val e = 0x55555555
    private val f = 0x66666666
    private val g = 0x77777777
    private val h = 0x08888888
    private val x = 0x99999999.toInt()

    private fun hx(id: Int) = "%08X".format(id)
    private fun set(vararg ids: Int) = TyreSet(ids.map { hx(it) })
    private val cur = listOf<Int?>(a, b, c, d)

    // D.2 plan

    @Test fun `plan 8 - targets equal to the block give nothing to write`() {
        assertEquals(TyrePlan.NothingToWrite, TyrePlans.build(cur, listOf(hx(a), hx(b), hx(c), hx(d))))
    }

    @Test fun `plan 9 - one changed wheel gives one entry for 3308`() {
        val r = TyrePlans.build(cur, listOf(hx(a), hx(b), hx(c), hx(e)))
        assertEquals(TyrePlan.Ok(listOf(TpmsPlanEntry(TpmsWire.DID_RR, d, e))), r)
    }

    @Test fun `plan 10 - lowercase and padded input is normalised and accepted`() {
        assertEquals("9A7BB4C2", TyrePlans.normalize("9a7bb4c2"))
        assertEquals("9A7BB4C2", TyrePlans.normalize(" 9A7BB4C2 "))
        val r = TyrePlans.build(cur, listOf(" 9a7bb4c2 ", null, null, null))
        assertEquals(TyrePlan.Ok(listOf(TpmsPlanEntry(TpmsWire.DID_FL, a, 0x9A7BB4C2.toInt()))), r)
    }

    @Test fun `plan 10 - inner spaces and a 0x prefix are rejected as hex`() {
        val r1 = TyrePlans.build(cur, listOf("9A7B B4C", null, null, null))
        val r2 = TyrePlans.build(cur, listOf("0x7BB4C2", null, null, null))
        assertEquals(TyrePlan.Invalid(TyrePlanError.INVALID_HEX, listOf(0)), r1)
        assertEquals(TyrePlan.Invalid(TyrePlanError.INVALID_HEX, listOf(0)), r2)
    }

    @Test fun `plan 11 - non hex, wrong length and reserved values are refused`() {
        assertEquals(TyrePlan.Invalid(TyrePlanError.INVALID_HEX, listOf(0)),
            TyrePlans.build(cur, listOf("9A7BB4CG", null, null, null)))
        assertEquals(TyrePlan.Invalid(TyrePlanError.INVALID_LENGTH, listOf(1)),
            TyrePlans.build(cur, listOf(null, "9A7BB4C", null, null)))
        assertEquals(TyrePlan.Invalid(TyrePlanError.INVALID_LENGTH, listOf(2)),
            TyrePlans.build(cur, listOf(null, null, "9A7BB4C2D", null)))
        assertEquals(TyrePlan.Invalid(TyrePlanError.INVALID_VALUE, listOf(3)),
            TyrePlans.build(cur, listOf(null, null, null, "00000000")))
        assertEquals(TyrePlan.Invalid(TyrePlanError.INVALID_VALUE, listOf(0)),
            TyrePlans.build(cur, listOf("FFFFFFFF", null, null, null)))
    }

    @Test fun `plan 12 - the same ID on two target wheels is a duplicate naming both`() {
        val r = TyrePlans.build(cur, listOf(hx(x), hx(x), null, null)) as TyrePlan.Invalid
        assertEquals(TyrePlanError.DUPLICATE, r.error)
        assertEquals(listOf(0, 1), r.wheels.sorted())
    }

    @Test fun `plan 13 - an ID that stays on another wheel is a duplicate`() {
        val r = TyrePlans.build(cur, listOf(hx(d), null, null, null)) as TyrePlan.Invalid
        assertEquals(TyrePlanError.DUPLICATE, r.error)
        assertEquals(setOf(0, 3), r.wheels.toSet())
    }

    @Test fun `plan 14 - a swap gives two entries and is valid`() {
        val r = TyrePlans.build(cur, listOf(hx(b), hx(a), hx(c), hx(d)))
        assertEquals(TyrePlan.Ok(listOf(
            TpmsPlanEntry(TpmsWire.DID_FL, a, b),
            TpmsPlanEntry(TpmsWire.DID_FR, b, a),
        )), r)
    }

    @Test fun `plan 15 - an empty or null target leaves that wheel alone`() {
        val r = TyrePlans.build(cur, listOf(null, "", "   ", hx(e)))
        assertEquals(TyrePlan.Ok(listOf(TpmsPlanEntry(TpmsWire.DID_RR, d, e))), r)
        assertNull(TyrePlans.normalize(null))
        assertNull(TyrePlans.normalize("  "))
    }

    @Test fun `plan 16 - an unread slot refuses the plan with NOT_READ`() {
        val r = TyrePlans.build(listOf(a, null, c, d), listOf(hx(e), null, null, null)) as TyrePlan.Invalid
        assertEquals(TyrePlanError.NOT_READ, r.error)
        val none = TyrePlans.build(listOf(null, null, null, null), listOf(null, null, null, null)) as TyrePlan.Invalid
        assertEquals(TyrePlanError.NOT_READ, none.error)
    }

    @Test fun `plan 17 - only RL in the target gives one entry for 3307`() {
        val r = TyrePlans.build(cur, listOf(null, null, hx(x), null))
        assertEquals(TyrePlan.Ok(listOf(TpmsPlanEntry(TpmsWire.DID_RL, c, x))), r)
    }

    @Test fun `plan 18 - four changes give entries in DID order`() {
        val r = TyrePlans.build(cur, listOf(hx(e), hx(f), hx(g), hx(h)))
        assertEquals(TyrePlan.Ok(listOf(
            TpmsPlanEntry(TpmsWire.DID_FL, a, e),
            TpmsPlanEntry(TpmsWire.DID_FR, b, f),
            TpmsPlanEntry(TpmsWire.DID_RL, c, g),
            TpmsPlanEntry(TpmsWire.DID_RR, d, h),
        )), r)
    }

    // D.8 set update

    private val sets = TyreSets(winter = set(a, b, c, d), summer = set(e, f, g, h))
    /** A snapshot that equals no saved set: the single-wheel fallback by the other three wheels decides. */
    private val noSet = listOf<Int?>(0x0A0A0A0A, 0x0B0B0B0B, 0x0C0C0C0C, 0x0D0D0D0D)

    @Test fun `update 63 - a written set read back as written stays unchanged`() {
        assertEquals(sets, TyreSetUpdate.afterWrite(sets, TyreSetName.WINTER, null, cur, listOf(a, b, c, d), true))
    }

    @Test fun `update 64 - a winter set written in a swapped order takes the swapped order`() {
        val r = TyreSetUpdate.afterWrite(sets, TyreSetName.WINTER, null, cur, listOf(b, a, c, d), true)
        assertEquals(set(b, a, c, d), r.winter)
        assertEquals(sets.summer, r.summer)
    }

    @Test fun `update 65 - an incomplete result leaves the sets as they were`() {
        assertEquals(sets, TyreSetUpdate.afterWrite(sets, TyreSetName.WINTER, null, cur, listOf(b, a, c, d), false))
    }

    @Test fun `single wheel - the set with the same other three takes the read back`() {
        val r = TyreSetUpdate.afterWrite(sets, null, 2, noSet, listOf(a, b, x, d), true)
        assertEquals(set(a, b, x, d), r.winter)
        assertEquals(sets.summer, r.summer)
    }

    @Test fun `single wheel - a read back matching neither set leaves the sets unchanged`() {
        assertEquals(sets, TyreSetUpdate.afterWrite(sets, null, 2, noSet, listOf(a, b, x, 0x01010101), true))
        assertEquals(sets, TyreSetUpdate.afterWrite(sets, null, 2, noSet, listOf(e, b, x, d), true))
    }

    @Test fun `single wheel - an incomplete result leaves the sets unchanged`() {
        assertEquals(sets, TyreSetUpdate.afterWrite(sets, null, 2, noSet, listOf(a, b, x, d), false))
    }

    @Test fun `D8 the set equal to the before IDs takes the single wheel write, another set with the same other three stays`() {
        val s = TyreSets(winter = set(a, b, c, d), summer = set(x, b, c, d))
        val r = TyreSetUpdate.afterWrite(s, null, 0, listOf(a, b, c, d), listOf(0x0E0E0E0E, b, c, d), true)
        assertEquals(set(0x0E0E0E0E, b, c, d), r.winter)
        assertEquals(s.summer, r.summer)
    }

    @Test fun `D8 without a set equal to the before IDs the unique other-three match decides`() {
        val s = TyreSets(winter = set(a, b, c, d), summer = set(e, f, g, h))
        val r = TyreSetUpdate.afterWrite(s, null, 2, listOf(a, b, 0x0F0F0F0F, d), listOf(a, b, x, d), true)
        assertEquals(set(a, b, x, d), r.winter)
        assertEquals(s.summer, r.summer)
    }

    // D.8 codec and backup

    @Test fun `codec 67 - sets survive a JSON round trip, empty cells included`() {
        val s = TyreSets(winter = TyreSet(listOf(hx(a), null, hx(c), hx(d))), summer = set(e, f, g, h))
        assertEquals(s, TyreSetsCodec.fromJson(TyreSetsCodec.toJson(s)))
        assertEquals(TyreSets(), TyreSetsCodec.fromJson(TyreSetsCodec.toJson(TyreSets())))
    }

    @Test fun `codec 67 - null, empty and malformed JSON give empty sets`() {
        for (j in listOf(null, "", "{", "[]", "not json", "{\"winter\":5}", "{\"winter\":{\"ids\":\"x\"}}")) {
            assertEquals(j, TyreSets(), TyreSetsCodec.fromJson(j))
        }
    }

    @Test fun `backup 67 - the tyre_sets key is in the settings part and in no other`() {
        val key = "tyre_sets"
        val (settingsSql, settingsArgs) = BackupParts.settingsKeyFilter(BackupPart.SETTINGS)
        assertTrue(settingsSql.startsWith("key NOT IN"))
        assertFalse(key in settingsArgs)
        for (part in listOf(BackupPart.TABLES, BackupPart.KEYS)) {
            val (sql, args) = BackupParts.settingsKeyFilter(part)
            assertTrue(sql.startsWith("key IN"))
            assertFalse(part.name, key in args)
        }
    }

    // D.9 wheel status

    private fun slot(did: Int, id: Int, heard: Boolean = true) =
        TpmsWire.parseSlot(did, hex("62%04X%08X%02X000101".format(did, id, if (heard) 0 else 1) + "1C0200"))

    private val slots = listOf(
        slot(0x3305, a), slot(0x3306, b), slot(0x3307, c), slot(0x3308, d),
    )

    @Test fun `status 68 - an active battery code lights only its wheel`() {
        val r = TyreWheelStatuses.of(slots, hex("5902FF500D0009"))
        assertEquals(listOf(TyreBattery.LOW, TyreBattery.OK, TyreBattery.OK, TyreBattery.OK), r.map { it.battery })
        assertEquals(a, r[0].id)
        assertEquals(true, r[0].heard)
    }

    @Test fun `status 68 - codes of the other three wheels map to their wheels`() {
        val r = TyreWheelStatuses.of(slots, hex("5902FF500E0009" + "500F0009" + "50100009"))
        assertEquals(listOf(TyreBattery.OK, TyreBattery.LOW, TyreBattery.LOW, TyreBattery.LOW), r.map { it.battery })
    }

    @Test fun `status 68 - another FTB of the same code is not battery low`() {
        val r = TyreWheelStatuses.of(slots, hex("5902FF501008" + "09"))
        assertEquals(TyreBattery.OK, r[3].battery)
    }

    @Test fun `status 68 - a stored only record is not battery low`() {
        val r = TyreWheelStatuses.of(slots, hex("5902FF500D0028"))
        assertEquals(TyreBattery.OK, r[0].battery)
    }

    @Test fun `status 68 - no DTC answer or a negative one gives unknown battery`() {
        for (dtc in listOf(null, hex("7F1931"))) {
            assertTrue(TyreWheelStatuses.of(slots, dtc).all { it.battery == TyreBattery.UNKNOWN })
        }
    }

    @Test fun `status 68 - a slot not heard shows heard false and an unread slot has no ID`() {
        val r = TyreWheelStatuses.of(listOf(slot(0x3305, a, heard = false), null, slots[2], slots[3]), hex("5902FF500D0009"))
        assertEquals(false, r[0].heard)
        assertNull(r[1].id)
        assertNull(r[1].heard)
    }
}
