package com.bydmate.app.voice

import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalVehicleQueryTest {

    @Test fun battery_charge_is_answered_from_snapshot() {
        assertEquals(
            LocalVehicleQuery.Reply("soc", "Заряд 64%."),
            LocalVehicleQuery.answer("какой заряд батареи", diParsData(soc = 64)),
        )
    }

    @Test fun outside_temperature_is_answered_from_snapshot() {
        assertEquals(
            LocalVehicleQuery.Reply("outside_temp", "Снаружи 11 градусов."),
            LocalVehicleQuery.answer("температура на улице", diParsData(exteriorTemp = 11)),
        )
    }

    @Test fun outside_temperature_asr_truncation_stays_local() {
        assertEquals(
            LocalVehicleQuery.Reply("outside_temp", "Снаружи 16 градусов."),
            LocalVehicleQuery.answer("температура на улиц", diParsData(exteriorTemp = 16)),
        )
    }

    @Test fun clipped_field_phrase_kotoraya_na_ulitse_stays_local() {
        assertEquals(
            LocalVehicleQuery.Reply("outside_temp", "Снаружи 10 градусов."),
            LocalVehicleQuery.answer("которая на улице", diParsData(exteriorTemp = 10)),
        )
    }

    @Test fun temperature_in_car_phrase_is_cabin_query() {
        assertEquals(
            LocalVehicleQuery.Reply("inside_temp", "В салоне 24 градусов."),
            LocalVehicleQuery.answer("температура в машине", diParsData(insideTemp = 24)),
        )
    }

    @Test fun temperature_inside_phrase_stays_local() {
        assertEquals(
            LocalVehicleQuery.Reply("inside_temp", "Температура в салоне недоступна."),
            LocalVehicleQuery.answer("температура внутри", diParsData(insideTemp = null)),
        )
    }

    @Test fun cabin_temperature_never_falls_back_to_climate_setpoint() {
        val data = diParsData(insideTemp = null, acTemp = 22)
        assertEquals(
            LocalVehicleQuery.Reply("inside_temp", "Температура в салоне недоступна."),
            LocalVehicleQuery.answer("температура в салоне", data),
        )
    }

    @Test fun cabin_temperature_uses_real_inside_sensor_when_present() {
        assertEquals(
            LocalVehicleQuery.Reply("inside_temp", "В салоне 21 градусов."),
            LocalVehicleQuery.answer("сколько градусов в салоне", diParsData(insideTemp = 21)),
        )
    }

    @Test fun explicit_climate_setpoint_query_is_local() {
        assertEquals(
            LocalVehicleQuery.Reply("climate_setpoint", "Климат установлен на 22 градусов."),
            LocalVehicleQuery.answer("какая температура климата", diParsData(acTemp = 22)),
        )
    }

    @Test fun ambiguous_temperature_stays_on_agent_path() {
        assertNull(LocalVehicleQuery.answer("какая температура", diParsData(exteriorTemp = 11)))
    }

    @Test fun action_phrase_is_not_misread_as_state_query() {
        assertNull(LocalVehicleQuery.answer("заряди батарею", diParsData(soc = 64)))
        assertNull(LocalVehicleQuery.answer("включи климат", diParsData(acTemp = 22)))
    }
}
