package com.bydmate.app.data.carstatus

/** Faults count and service countdown from the stock car status provider; null = not shown. */
data class CarServiceStatus(val faults: Int?, val daysLeft: Int?, val kmLeft: Int?)

/** Pure mapping of the provider's key/value rows to [CarServiceStatus]. */
object CarStatusParser {
    fun parse(rows: Map<String, String?>): CarServiceStatus = TODO("not implemented")
}
