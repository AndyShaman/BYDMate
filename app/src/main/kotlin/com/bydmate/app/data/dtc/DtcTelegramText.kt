@file:Suppress("UnusedParameter", "UnusedPrivateProperty", "FunctionOnlyReturningConstant", "EmptyFunctionBlock") // compile stub, remove with the implementation

package com.bydmate.app.data.dtc

/** The texts of the scan message and card, in the app language; a plain implementation in tests. */
interface DtcLabels {
    val title: String
    fun checked(at: String, answered: Int, total: Int): String
    val nowHeader: String
    val oldHeader: String
    val pendingMark: String
    val noErrors: String
    val footer: String
    fun blockName(name: DtcBlockName): String
}

object DtcTelegramText {
    /** Plain text (no markup) for one manual send; [checkedAt] is the time already formatted. */
    fun build(summary: DtcScanSummary, checkedAt: String, labels: DtcLabels): String = ""
}
