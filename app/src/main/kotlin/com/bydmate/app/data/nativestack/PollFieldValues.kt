package com.bydmate.app.data.nativestack

/**
 * The per-field values the last poll tick decoded, kept so the dump can put the poll and the
 * push channel side by side (`poll≠push`).
 *
 * Written from the map [NativeParsReader] has already built for the snapshot — nothing here
 * reads anything from the car.
 */
object PollFieldValues {

    @Volatile
    private var values: Map<String, Any?> = emptyMap()

    fun record(decoded: Map<String, Any?>) {
        values = decoded
    }

    fun latest(): Map<String, Any?> = values
}
