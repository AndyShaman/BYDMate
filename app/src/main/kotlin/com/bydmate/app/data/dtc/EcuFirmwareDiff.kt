package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.EcuFirmware

/** One block in a saved firmware check: raw F194 and F195 answers (null = no answer). */
class FirmwareBlock(val addr: Int, val f194Uds: ByteArray?, val f195Uds: ByteArray?)

/** A finished «Проверить сейчас»: wall time, head-unit build (Build.FINGERPRINT) and every address. */
class FirmwareCheck(val at: Long, val huBuild: String, val blocks: List<FirmwareBlock>)

enum class FirmwareChange {
    /** F195 raw bytes differ from the previous check (version or date). */
    UPDATED,
    UNCHANGED,
    /** No previous check, or the address was absent from it. */
    FIRST,
    /** Answered F195 in the previous check, no answer now. */
    GONE,
    /** No F195 answer now and none before. */
    SILENT,
}

class FirmwareRow(val addr: Int, val name: DtcBlockName, val change: FirmwareChange, val before: EcuFirmware?, val after: EcuFirmware?)

object EcuFirmwareDiff {
    const val HISTORY = 10

    /** Rows of [current] against [previous], in [current]'s address order; names via DtcBlockNames.identify. */
    fun diff(previous: FirmwareCheck?, current: FirmwareCheck): List<FirmwareRow> = TODO()

    /** [check] appended; the oldest dropped past [HISTORY]. */
    fun append(history: List<FirmwareCheck>, check: FirmwareCheck): List<FirmwareCheck> = TODO()

    /**
     * Blue «Магнитола обновилась» row: [current] blank → false; with a saved check → current differs
     * from the newest check's build; with no check → current differs from [baseline] (null → false).
     */
    fun huChanged(history: List<FirmwareCheck>, baseline: String?, current: String): Boolean = TODO()

    /** JSON of the history; null, blank or malformed → empty list, never a throw. */
    fun toJson(history: List<FirmwareCheck>): String = TODO()
    fun fromJson(json: String?): List<FirmwareCheck> = TODO()
}
