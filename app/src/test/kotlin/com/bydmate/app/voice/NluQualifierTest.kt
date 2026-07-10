package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class NluQualifierTest {

    private fun cmd(text: String): String? =
        (NluParser.parse(text, VoiceLang.RU) as? ParseResult.Command)?.command

    private fun unrecognized(text: String) =
        assertEquals(ParseResult.Unrecognized, NluParser.parse(text, VoiceLang.RU))

    // Corner windows: a compound qualifier must select exactly ONE window.
    @Test fun rear_right_window_targets_one_window() = assertEquals("后右打开100", cmd("открой заднее правое окно"))
    @Test fun rear_left_close() = assertEquals("后左打开0", cmd("закрой заднее левое окно"))
    @Test fun front_left_is_driver() = assertEquals("主驾打开100", cmd("открой переднее левое окно"))
    @Test fun front_right_is_passenger() = assertEquals("副驾打开0", cmd("закрой переднее правое окно"))
    @Test fun bare_left_is_rear_left() = assertEquals("后左打开100", cmd("открой левое окно"))
    @Test fun bare_window_still_all() = assertEquals("车窗全开", cmd("открой окно"))
    @Test fun front_windows_still_pair() = assertEquals("前排车窗全开", cmd("открой передние окна"))

    // Front trunk is NOT the rear tailgate — must go to the agent.
    @Test fun front_trunk_goes_to_agent() = unrecognized("открой передний багажник")
    @Test fun rear_trunk_still_resolves() = assertEquals("开后备箱", cmd("открой задний багажник"))
    @Test fun bare_trunk_still_resolves() = assertEquals("开后备箱", cmd("открой багажник"))

    // Seat without a side qualifier defaults to the driver.
    @Test fun seat_defaults_to_driver() = assertEquals("主驾座椅加热1档", cmd("включи подогрев сиденья"))
    @Test fun seat_level_2_driver_default() = assertEquals("主驾座椅加热2档", cmd("подогрев сиденья на 2"))
    @Test fun seat_passenger_still_narrows() = assertEquals("副驾座椅加热1档", cmd("включи подогрев сиденья пассажира"))
    @Test fun seat_vent_defaults_to_driver() = assertEquals("主驾座椅通风1档", cmd("включи вентиляцию сиденья"))

    // Levels 4/5 exist only in the agent catalog — NLU must not silently fire level 1.
    @Test fun seat_level_4_goes_to_agent() = unrecognized("подогрев сиденья на 4")

    // Negation is beyond slot NLU.
    @Test fun negation_goes_to_agent() = unrecognized("не открывай окно")
    @Test fun negation_no_goes_to_agent() = unrecognized("нет закрой люк")

    // "машина" as the lock target with open/close verbs.
    @Test fun close_car_locks() = assertEquals("车门上锁", cmd("закрой машину"))
    @Test fun open_car_unlocks() = assertEquals("车门解锁", cmd("открой машину"))

    // Cabin chatter ("закрой дверь" said to a passenger) must NOT silently lock/unlock
    // the car via open/close verbs; only the explicit "машина" surface fast-paths.
    @Test fun close_door_goes_to_agent() = unrecognized("закрой дверь")
    @Test fun close_doors_goes_to_agent() = unrecognized("закрой двери")
    @Test fun open_door_goes_to_agent() = unrecognized("открой дверь")
    @Test fun lock_doors_still_locks() = assertEquals("车门上锁", cmd("запри двери"))
    @Test fun lock_car_still_locks() = assertEquals("车门上锁", cmd("запри машину"))
}
