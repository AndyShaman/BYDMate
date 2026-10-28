@file:Suppress("UnusedParameter", "UnusedPrivateProperty", "FunctionOnlyReturningConstant", "EmptyFunctionBlock") // compile stub, remove with the implementation

package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.DtcAddressResult

enum class DtcKind { NOW, OLD, PENDING }

/** Fault type from the third code byte; only the five known values have a text. */
enum class DtcFault { NO_SIGNAL, OPEN_CIRCUIT, SHORT_TO_GROUND, SHORT_TO_PLUS, COMPONENT }

/** `59 01` answer: availability [mask], DTC [format] and [count]. */
data class DtcCount(val mask: Int, val format: Int, val count: Int)

data class DtcRow(val code: String, val status: Int, val kind: DtcKind, val ftb: Int)

/** [records] = every 4-byte record read; [rows] = the visible ones. [negative] = the block answered an NRC. */
data class DtcList(val records: Int, val rows: List<DtcRow>, val negative: Boolean)

data class DtcFound(val addr: Int, val row: DtcRow, val block: DtcBlockName)

/** [found]: NOW rows first, then PENDING and OLD in address order. */
data class DtcScanSummary(
    val answered: Int,
    val total: Int,
    val now: Int,
    val old: Int,
    val pending: Int,
    val found: List<DtcFound>,
)

object DtcParser {
    /** Null for a negative or too short answer. */
    fun parseCount(uds: ByteArray): DtcCount? = null

    /** [format] is the DTC format byte of the count answer, null when unknown. */
    fun parseList(uds: ByteArray, format: Int?): DtcList = DtcList(0, emptyList(), false)

    /** Null = the record is dropped (not tested, no failure bit). */
    fun classify(status: Int): DtcKind? = null

    /** `U0164-87` for format 00, raw hex `D16687` for format 01, unknown or null. */
    fun codeText(code: ByteArray, format: Int?): String = ""

    fun faultType(ftb: Int): DtcFault? = null

    fun summarize(results: List<DtcAddressResult>): DtcScanSummary = DtcScanSummary(0, 0, 0, 0, 0, emptyList())
}
