package com.bydmate.app.ui.tech

/**
 * Display unit for tyre pressure readings (bar vs psi).
 * Car sensors report pressure in kPa; callers convert kPa -> bar before calling [convert].
 */
object TyrePressureUnit {
    enum class Unit { BAR, PSI }

    const val SETTING_BAR = "bar"
    const val SETTING_PSI = "psi"
    private const val PSI_PER_BAR = 14.503773773

    fun fromSetting(value: String?): Unit = when (value) {
        SETTING_PSI -> Unit.PSI
        else -> Unit.BAR
    }

    fun convert(bar: Double, unit: Unit): Double = when (unit) {
        Unit.BAR -> bar
        Unit.PSI -> bar * PSI_PER_BAR
    }
}
