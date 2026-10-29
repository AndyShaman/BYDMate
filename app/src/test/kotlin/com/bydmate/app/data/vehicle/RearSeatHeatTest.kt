package com.bydmate.app.data.vehicle

import com.bydmate.app.data.nativestack.FidCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Inline rear seat catalogs shared by the rear seat heat tests (#327). */
internal object RearSeatCatalogs {
    private fun dump(has: Pair<Int, Int>, state: Pair<Int, Int>, set: Pair<Int, Int>) = """
        Setting.SET_HAS_REAR_LEFT_SEAT_HEATING=${has.first}
        Setting.SET_HAS_REAR_RIGHT_SEAT_HEATING=${has.second}
        Setting.SET_REAR_LEFT_SEAT_HEATING_STATE=${state.first}
        Setting.SET_REAR_RIGHT_SEAT_HEATING_STATE=${state.second}
        Setting.SET_REAR_LEFT_SEAT_HEATING_STATE_SET=${set.first}
        Setting.SET_REAR_RIGHT_SEAT_HEATING_STATE_SET=${set.second}
    """.trimIndent()

    /** Han DL4 (also Leopard 3 and Sea Lion 07). */
    val dl4: FidCatalog = FidCatalog.parse(dump(-933232629 to -933232621, 1214251019 to 1214251027, 1125122091 to 1125122099))

    /** Song Plus DL3: HAS and STATE differ, SET is the same. */
    val songPlus: FidCatalog = FidCatalog.parse(dump(-811597781 to -811597773, 1335885867 to 1335885875, 1125122091 to 1125122099))

    val withoutHas: FidCatalog = FidCatalog.parse(
        dl4.symbols.filterKeys { !it.startsWith("Setting.SET_HAS_REAR_LEFT") }.entries.joinToString("\n") { "${it.key}=${it.value}" },
    )

    val setMismatch: FidCatalog = FidCatalog.parse(dump(-933232629 to -933232621, 1214251019 to 1214251027, 1125122100 to 1125122099))

    const val HAS_LEFT = -933232629
    const val STATE_LEFT = 1214251019
}

class RearSeatHeatTest {

    @Test fun `addresses come from the cars own catalog`() {
        assertEquals(
            RearSeatAddresses(-933232629, 1214251019, 1125122091),
            RearSeatHeat.addresses(RearSeatCatalogs.dl4, RearSeat.LEFT),
        )
        assertEquals(
            RearSeatAddresses(-933232621, 1214251027, 1125122099),
            RearSeatHeat.addresses(RearSeatCatalogs.dl4, RearSeat.RIGHT),
        )
    }

    @Test fun `Song Plus has its own has and state fids and the same set fid`() {
        assertEquals(
            RearSeatAddresses(-811597781, 1335885867, 1125122091),
            RearSeatHeat.addresses(RearSeatCatalogs.songPlus, RearSeat.LEFT),
        )
    }

    @Test fun `no addresses without the catalog, a symbol, or the known set fid`() {
        assertNull(RearSeatHeat.addresses(null, RearSeat.LEFT))
        assertNull(RearSeatHeat.addresses(RearSeatCatalogs.withoutHas, RearSeat.LEFT))
        assertNull(RearSeatHeat.addresses(RearSeatCatalogs.setMismatch, RearSeat.LEFT))
    }

    @Test fun `constants and symbols`() {
        assertEquals(1023, RearSeatHeat.READ_DEV)
        assertEquals(1125122091, RearSeatHeat.SET_FID_LEFT)
        assertEquals(1125122099, RearSeatHeat.SET_FID_RIGHT)
        assertEquals(2, RearSeatHeat.MAX_LEVEL)
        assertEquals("rear_left_seat_heat", RearSeatHeat.actionName(RearSeat.LEFT))
        assertEquals("rear_right_seat_heat", RearSeatHeat.actionName(RearSeat.RIGHT))
        assertEquals(setOf("rear_left_seat_heat", "rear_right_seat_heat"), RearSeatHeat.ACTIONS)
        assertEquals(
            setOf(
                "Setting.SET_HAS_REAR_LEFT_SEAT_HEATING", "Setting.SET_HAS_REAR_RIGHT_SEAT_HEATING",
                "Setting.SET_REAR_LEFT_SEAT_HEATING_STATE", "Setting.SET_REAR_RIGHT_SEAT_HEATING_STATE",
                "Setting.SET_REAR_LEFT_SEAT_HEATING_STATE_SET", "Setting.SET_REAR_RIGHT_SEAT_HEATING_STATE_SET",
            ),
            RearSeatHeat.SYMBOLS.toSet(),
        )
    }

    @Test fun `the car takes level plus one`() {
        assertEquals(listOf(1, 2, 3), (0..2).map { RearSeatHeat.writeValue(it) })
    }
}
