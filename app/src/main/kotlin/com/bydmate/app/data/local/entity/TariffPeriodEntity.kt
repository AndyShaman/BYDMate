package com.bydmate.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One electricity price period: the rates that apply from [startTs] until the next
 * period starts. There are no gaps — the earliest period covers everything before it,
 * the latest one everything after. A period dated in the future is allowed.
 *
 * Currency is NOT part of a period: it stays a single global setting.
 */
@Entity(
    tableName = "tariff_periods",
    indices = [Index(value = ["start_ts"], unique = true)]
)
data class TariffPeriodEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Local midnight of the first day the period applies to. */
    @ColumnInfo(name = "start_ts") val startTs: Long,
    /** Price of 1 kWh taken from the wall socket at home (AC). */
    @ColumnInfo(name = "home_rate") val homeRate: Double,
    /** Price of 1 kWh at a fast charger (DC). */
    @ColumnInfo(name = "dc_rate") val dcRate: Double,
    /** AC charging losses, %: the meter counts more than the pack receives. */
    @ColumnInfo(name = "ac_loss_pct") val acLossPct: Double = DEFAULT_AC_LOSS_PCT,
    /** DC charging losses, %. */
    @ColumnInfo(name = "dc_loss_pct") val dcLossPct: Double = DEFAULT_DC_LOSS_PCT,
    /** How a trip is priced: [TRIP_RULE_HOME], [TRIP_RULE_DC], [TRIP_RULE_CHARGES] or a number. */
    @ColumnInfo(name = "trip_rule") val tripRule: String = TRIP_RULE_HOME,
) {
    companion object {
        /** Typical on-board-charger + cable loss on AC (ADAC/DLR measurements: 8-15%). */
        const val DEFAULT_AC_LOSS_PCT = 10.0
        /** DC skips the on-board charger, so losses are roughly half of AC. */
        const val DEFAULT_DC_LOSS_PCT = 5.0

        const val TRIP_RULE_HOME = "home"
        const val TRIP_RULE_DC = "dc"
        const val TRIP_RULE_CHARGES = "charges"
    }
}
