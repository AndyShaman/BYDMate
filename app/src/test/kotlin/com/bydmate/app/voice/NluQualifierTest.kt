package com.bydmate.app.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class NluQualifierTest {

    private fun cmd(text: String): String? =
        (NluParser.parse(text) as? ParseResult.Command)?.command

    private fun unrecognized(text: String) =
        assertEquals(ParseResult.Unrecognized, NluParser.parse(text))

    private fun refused(text: String, reason: String) =
        assertEquals(ParseResult.Refused(reason), NluParser.parse(text))

    // Corner windows: a compound qualifier must select exactly ONE window.
    @Test fun rear_right_window_targets_one_window() = assertEquals("后右打开100", cmd("открой заднее правое окно"))
    @Test fun rear_left_close() = assertEquals("后左打开0", cmd("закрой заднее левое окно"))
    @Test fun front_left_is_driver() = assertEquals("主驾打开100", cmd("открой переднее левое окно"))
    @Test fun front_right_is_passenger() = assertEquals("副驾打开0", cmd("закрой переднее правое окно"))
    @Test fun bare_left_is_rear_left() = assertEquals("后左打开100", cmd("открой левое окно"))
    // Singular "окно" / "моё окно" is the driver's (Andy 2026-09-24); plural "окна" is every window.
    @Test fun bare_window_is_driver() = assertEquals("主驾打开100", cmd("открой окно"))
    @Test fun my_window_is_driver() = assertEquals("主驾打开100", cmd("открой моё окно"))
    @Test fun plural_windows_are_all() = assertEquals("车窗全开", cmd("открой окна"))
    @Test fun front_windows_still_pair() = assertEquals("前排车窗全开", cmd("открой передние окна"))

    // A qualifier set maps to exactly one window or pair, every qualifier used, or to nothing:
    // a corner of each row never collapses to all four, a row never beats the side.
    @Test fun qualifiers_naming_no_single_window_or_pair_are_not_placed() {
        for (text in listOf("открой заднее левое и переднее правое окно", "открой левое и правое окно")) {
            refused(text, VoiceRefusal.MULTIPLE_COMMANDS)
        }
        for (text in listOf("открой заднее окно водителя", "открой правое окно водителя",
            "открой все левые окна")) {
            unrecognized(text)
        }
        assertEquals("车窗全开", cmd("открой передние и задние окна"))
        assertEquals("后排车窗全开", cmd("открой все задние окна"))
    }

    // Qualifiers are read word for word in their listed forms, never by prefix or by stem.
    @Test fun qualifier_words_are_read_only_in_their_forms() {
        unrecognized("открой окно сторонник")
        unrecognized("открой окно лев")
        assertEquals("主驾打开100", cmd("открой окно на водительской стороне"))
        assertEquals("副驾打开100", cmd("открой окно у пассажира"))
        assertEquals("后左打开0", cmd("закрой левую форточку сзади"))
    }

    // Half and vent exist for every single window and for the front/rear pairs,
    // not only for "all windows".
    @Test fun vent_driver_window_ajar() = assertEquals("主驾通风", cmd("приоткрой окно водителя"))
    @Test fun half_driver_window_with_open_verb() = assertEquals("主驾半开", cmd("открой наполовину водительское окно"))
    @Test fun vent_rear_left_window_ajar() = assertEquals("后左通风", cmd("приоткрой заднее левое окно"))
    @Test fun half_passenger_window() = assertEquals("副驾半开", cmd("окно пассажира наполовину"))
    @Test fun half_rear_pair() = assertEquals("后排车窗半开", cmd("задние окна наполовину"))
    @Test fun vent_front_pair_ajar() = assertEquals("前排车窗通风", cmd("приоткрой передние окна"))
    @Test fun vent_front_pair() = assertEquals("前排车窗通风", cmd("проветри передние окна"))
    @Test fun vent_rear_pair() = assertEquals("后排车窗通风", cmd("проветри задние окна"))
    @Test fun half_sunroof_with_open_verb() = assertEquals("天窗打开50", cmd("открой люк наполовину"))

    // An explicit percentage picks the widest detent that does not exceed it (never above);
    // a measure the parser cannot read is refused, never a full open.
    @Test fun window_percentage_picks_detent_below() = assertEquals("主驾通风", cmd("открой водительское окно на двадцать процентов"))
    @Test fun sunroof_percentage_picks_detent_below() = assertEquals("天窗通风", cmd("открой люк на тридцать процентов"))
    @Test fun unread_unit_is_unrecognized() = unrecognized("открой окно на пять сантиметров")

    // Front trunk is NOT the rear tailgate — must go to the agent.
    @Test fun front_trunk_goes_to_agent() = unrecognized("открой передний багажник")
    @Test fun rear_trunk_still_resolves() = assertEquals("开后备箱", cmd("открой задний багажник"))
    @Test fun bare_trunk_still_resolves() = assertEquals("开后备箱", cmd("открой багажник"))

    // Seat without a side qualifier defaults to the driver.
    @Test fun seat_defaults_to_driver() = assertEquals("主驾座椅加热1档", cmd("включи подогрев сиденья"))
    @Test fun seat_level_2_driver_default() = assertEquals("主驾座椅加热2档", cmd("подогрев сиденья на 2"))
    @Test fun seat_passenger_still_narrows() = assertEquals("副驾座椅加热1档", cmd("включи подогрев сиденья пассажира"))
    @Test fun seat_vent_defaults_to_driver() = assertEquals("主驾座椅通风1档", cmd("включи вентиляцию сиденья"))

    // Left-hand drive: RIGHT is the passenger seat, LEFT the driver's; a side never falls through
    // to the driver default, and contradicting sides go to the agent.
    @Test fun seat_side_words_pick_their_seat() {
        assertEquals("副驾座椅加热1档", cmd("включи подогрев переднего правого сиденья"))
        assertEquals("副驾座椅加热1档", cmd("включи подогрев правого кресла"))
        assertEquals("主驾座椅加热1档", cmd("включи подогрев левого кресла"))
        assertEquals(listOf("主驾座椅加热1档", "副驾座椅加热1档"),
            (NluParser.parse("включи подогрев сидений слева и справа") as? ParseResult.Command)?.commands)
        unrecognized("включи подогрев правого сиденья водителя")
        unrecognized("включи подогрев левого сиденья пассажира")
    }

    // Levels 4/5 exist only in the agent catalog — NLU must not silently fire level 1.
    @Test fun seat_level_4_goes_to_agent() = unrecognized("подогрев сиденья на 4")

    // Negation is beyond slot NLU.
    @Test fun negation_is_refused() = refused("не открывай окно", VoiceRefusal.NEGATION)
    @Test fun negation_no_is_refused() = refused("нет закрой люк", VoiceRefusal.NEGATION)

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
