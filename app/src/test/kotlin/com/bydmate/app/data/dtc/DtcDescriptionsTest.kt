package com.bydmate.app.data.dtc

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Tables from `src/test/resources/dtc/` (the real Ti3 61337 rows plus synthetic ones). */
class DtcDescriptionsTest {

    private fun res(name: String) =
        requireNotNull(DtcDescriptionsTest::class.java.getResourceAsStream("/dtc/$name")) { name }.reader(Charsets.UTF_8)

    private val tables = DtcTablesLoader.parse(res("texts.tsv"), res("manuals.tsv"), res("rows.tsv"), res("models.tsv"))
    private val d = DtcDescriptions(tables)

    @Test fun `model 260, ADAS block, U116687 is the exact manual text`() {
        val r = d.lookup(260, DtcEnergy.EV, "ADAS", "U116687")!!
        assertEquals(DtcDescLevel.EXACT, r.level)
        assertEquals("Lost CAN communication with SEC", r.en)
        assertEquals("与SEC丢失CAN通信", r.zh)
    }

    @Test fun `the same code in another block of the same manual means something else`() {
        val r = d.lookup(260, DtcEnergy.EV, "BODY_RIGHT", "U116687")!!
        assertEquals(DtcDescLevel.EXACT, r.level)
        assertEquals("与副仪表板左氛围灯前灯头失去通讯", r.zh)
    }

    @Test fun `the other real rows are exact too`() {
        assertEquals("与底盘网Left_BCM通讯丢失", d.lookup(260, DtcEnergy.EV, "URE", "U171187")!!.zh)
        assertEquals("与空调通讯故障", d.lookup(260, DtcEnergy.UNKNOWN, "URE", "U016487")!!.zh)
        assertEquals("（针对DST配置打开时）DST报文丢失", d.lookup(260, DtcEnergy.EV, "STEERING", "U111387")!!.zh)
    }

    @Test fun `the exact text comes from the model's manual that has the code in this block`() {
        // 90002 is preferred but has U400187 only in another block; 90001 has it in BMS
        val r = d.lookup(999, DtcEnergy.UNKNOWN, "BMS", "U400187")!!
        assertEquals(DtcDescLevel.EXACT, r.level)
        assertEquals("90001", r.manual)
        assertEquals("Battery communication fault", r.en)
    }

    @Test fun `unknown class with two texts for the code is not picked`() {
        assertNull(d.lookup(260, DtcEnergy.EV, null, "U116687"))
    }

    @Test fun `unknown class with one text for the code is exact`() {
        val r = d.lookup(260, DtcEnergy.EV, null, "U171187")!!
        assertEquals(DtcDescLevel.EXACT, r.level)
    }

    @Test fun `a code in no manual has no description`() {
        assertNull(d.lookup(260, DtcEnergy.EV, "URE", "U10FFCE"))
        assertNull(d.lookup(null, DtcEnergy.UNKNOWN, "URE", "U10FFCE"))
    }

    @Test fun `unknown model with equal English in several manuals is similar`() {
        val r = d.lookup(null, DtcEnergy.UNKNOWN, "BRAKES", "U200187")!!
        assertEquals(DtcDescLevel.SIMILAR, r.level)
        assertEquals("Brake pedal sensor fault", r.en)
        assertEquals("制动踏板传感器故障甲", r.zh)
    }

    @Test fun `unknown model with differing English is no description`() {
        assertNull(d.lookup(null, DtcEnergy.UNKNOWN, "BRAKES", "U200287"))
    }

    @Test fun `unknown model needs a known class for similar`() {
        assertNull(d.lookup(null, DtcEnergy.UNKNOWN, null, "U200187"))
    }

    @Test fun `a model without a manual row falls back to similar`() {
        assertEquals(DtcDescLevel.SIMILAR, d.lookup(1234, DtcEnergy.UNKNOWN, "BRAKES", "U200187")!!.level)
    }

    @Test fun `the energy filter drops the manual that contradicts the car`() {
        assertEquals("Battery fault EV manual", d.lookup(999, DtcEnergy.EV, "BMS", "U300187")!!.en)
        assertEquals("Battery fault DM manual", d.lookup(999, DtcEnergy.DM, "BMS", "U300187")!!.en)
        // unknown kind keeps all, the first in the model's order wins
        assertEquals("Battery fault DM manual", d.lookup(999, DtcEnergy.UNKNOWN, "BMS", "U300187")!!.en)
        assertEquals(DtcDescLevel.EXACT, d.lookup(999, DtcEnergy.EV, "BMS", "U300187")!!.level)
    }

    @Test fun `a hybrid car does not take an EV-only manual as exact`() {
        val r = d.lookup(260, DtcEnergy.DM, "ADAS", "U116687")
        assertNotNull(r)
        assertEquals(DtcDescLevel.SIMILAR, r!!.level)
    }

    @Test fun `a malformed line is skipped, the rest still loads`() {
        // rows.tsv carries a line without tabs and a short one
        assertEquals(DtcDescLevel.EXACT, d.lookup(260, DtcEnergy.EV, "ADAS", "U116687")!!.level)
        val broken = DtcTablesLoader.parse(
            StringReader("t1\tzh\ten\nbad\n"), StringReader("1\tEV\tS\nbad\n"),
            StringReader("1\tADAS\tU100087\tt1\nbad line\n1\tADAS\tU100087\tmissing\n"),
            StringReader("7\t1\nnonsense\n"),
        )
        assertEquals("en", DtcDescriptions(broken).lookup(7, DtcEnergy.EV, "ADAS", "U100087")!!.en)
    }

    @Test fun `empty tables give no descriptions and do not crash`() {
        val empty = DtcTablesLoader.parse(StringReader(""), StringReader(""), StringReader(""), StringReader(""))
        assertNull(DtcDescriptions(empty).lookup(260, DtcEnergy.EV, "ADAS", "U116687"))
    }
}
