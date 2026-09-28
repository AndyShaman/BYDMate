package com.bydmate.app.voice

import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleQuestionTest {

    @Test fun `russian plural forms`() {
        val forms = listOf(0, 1, 2, 4, 5, 11, 12, 14, 21, 22, 25, 101, 111, 112, 121)
            .associateWith { VehicleQuestion.plural(it, "градус", "градуса", "градусов") }
        assertEquals(
            mapOf(
                0 to "градусов", 1 to "градус", 2 to "градуса", 4 to "градуса", 5 to "градусов",
                11 to "градусов", 12 to "градусов", 14 to "градусов", 21 to "градус", 22 to "градуса",
                25 to "градусов", 101 to "градус", 111 to "градусов", 112 to "градусов", 121 to "градус",
            ),
            forms,
        )
    }

    @Test fun `outside temperature, below zero said with минус`() {
        fun say(t: Int) = VehicleQuestion.OUTSIDE_TEMP.answer(diParsData(exteriorTemp = t), null)
        assertEquals("Снаружи 19 градусов.", say(19))
        assertEquals("Снаружи 21 градус.", say(21))
        assertEquals("Снаружи 0 градусов.", say(0))
        assertEquals("Снаружи минус 5 градусов.", say(-5))
        assertEquals("Снаружи минус 1 градус.", say(-1))
        assertEquals("Снаружи минус 22 градуса.", say(-22))
    }

    @Test fun `cabin temperature`() {
        assertEquals("В салоне 22 градуса.", VehicleQuestion.CABIN_TEMP.answer(diParsData(insideTemp = 22), null))
        assertEquals("В салоне 11 градусов.", VehicleQuestion.CABIN_TEMP.answer(diParsData(insideTemp = 11), null))
    }

    @Test fun `battery charge in percent`() {
        fun say(soc: Int) = VehicleQuestion.SOC.answer(diParsData(soc = soc), null)
        assertEquals("Заряд 65 процентов.", say(65))
        assertEquals("Заряд 1 процент.", say(1))
        assertEquals("Заряд 23 процента.", say(23))
        assertEquals("Заряд 100 процентов.", say(100))
    }

    @Test fun `range in kilometres from the range the dashboard shows`() {
        fun say(km: Double) = VehicleQuestion.RANGE.answer(diParsData(soc = 65), km)
        assertEquals("Запас хода 214 километров.", say(214.4))
        assertEquals("Запас хода 221 километр.", say(220.6))
        assertEquals("Запас хода 32 километра.", say(32.0))
    }

    @Test fun `climate set temperature`() {
        assertEquals("Климат на 19 градусов.", VehicleQuestion.AC_TEMP.answer(diParsData(acTemp = 19), null))
        assertEquals("Климат на 21 градус.", VehicleQuestion.AC_TEMP.answer(diParsData(acTemp = 21), null))
    }

    @Test fun `an unknown value has no answer, never a zero`() {
        val empty = diParsData()
        VehicleQuestion.entries.forEach { q ->
            assertNull("$q without a snapshot", q.answer(null, null))
            assertNull("$q with nothing read", q.answer(empty, null))
        }
        // A cold-start 0 is not a charge or a climate setpoint.
        assertNull(VehicleQuestion.SOC.answer(diParsData(soc = 0), null))
        assertNull(VehicleQuestion.AC_TEMP.answer(diParsData(acTemp = 0), null))
    }

    @Test fun `ASK in the dictionary runs the question`() {
        val d = VoiceDictionary.parse("solo какой заряд => ASK soc")
        assertEquals(ParseResult.Ask(VehicleQuestion.SOC), d.match(VoiceDictionary.words("какой заряд")))
    }

    @Test fun `an unknown question in the dictionary is a loader error`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            VoiceDictionary.parse("solo какой бензин => ASK fuel")
        }
        assertTrue(e.message, "fuel" in e.message.orEmpty())
    }

    @Test fun `every question is asked by some phrase of the dictionary`() {
        val asked = VoiceDictionary.load().phrases().mapNotNull { (it.second as? ParseResult.Ask)?.question }.toSet()
        assertEquals(VehicleQuestion.entries.toSet(), asked)
    }
}
