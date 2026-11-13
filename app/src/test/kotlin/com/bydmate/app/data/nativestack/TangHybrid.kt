package com.bydmate.app.data.nativestack

/** Shared inputs of the hybrid fid tests: the Tang DM 2021 catalog (#394) and its live answers. */
internal object TangHybrid {
    const val MILEAGE_HEV = 1033220136
    const val MILEAGE_EV = 1033207840
    const val FUEL_PERCENT = 1033543736
    const val FUEL_RANGE = 1033195552
    const val FUEL_TOTAL = 1033211920
    const val EV_RANGE = 1033203771

    fun floatRaw(value: Float): Int = java.lang.Float.floatToRawIntBits(value)

    fun catalogText(): String =
        checkNotNull(TangHybrid::class.java.classLoader?.getResource("fid-catalog-tang-dm-2021.txt")) {
            "missing fixture"
        }.readText()

    fun catalog(): FidCatalog = FidCatalog.parse(catalogText())

    /** Plausible live words at the catalog addresses, by fid. */
    val liveAtCatalog: Map<Int, Int> = mapOf(
        MILEAGE_HEV to 52000,
        MILEAGE_EV to 31000,
        FUEL_PERCENT to 40,
        FUEL_RANGE to 350,
        FUEL_TOTAL to floatRaw(4100.5f),
        EV_RANGE to 60,
    )

    /** The same live words at the compiled address of each field. */
    val liveAtCompiled: Map<Int, Int> = mapOf(
        fid("mileageHevKm") to 52000,
        fid("mileageEvKm") to 31000,
        fid("fuelPercent") to 40,
        fid("fuelRangeKm") to 350,
        fid("fuelTotalL") to floatRaw(4100.5f),
        fid("evRangeKm") to 60,
    )

    /** The words an EV answers at the compiled hybrid addresses ("absent" markers). */
    val evWordsAtCompiled: Map<Int, Int> = mapOf(
        fid("mileageHevKm") to 1048575,
        fid("mileageEvKm") to 1048575,
        fid("fuelPercent") to 255,
        fid("fuelRangeKm") to 2046,
        fid("fuelTotalL") to floatRaw(0.0f),
        fid("evRangeKm") to 2046,
    )

    /** Every compiled hybrid address answering the link error -10011. */
    val deadAtCompiled: Map<Int, Int> = FidMap.hybrid.associate { it.fid to -10011 }

    private fun fid(field: String): Int = FidMap.hybrid.first { it.field == field }.fid

    /** A probe answering from [answers] by fid; an address not listed gives no answer (null). */
    fun probe(answers: Map<Int, Int>): FidProbe = FidProbe { requests -> requests.map { answers[it.fid] } }

    /** The table the resolver is expected to produce on the Tang catalog, built by hand. */
    fun table(): ResolvedFidTable {
        val catalogFid = mapOf(
            "mileageHevKm" to MILEAGE_HEV, "mileageEvKm" to MILEAGE_EV, "fuelPercent" to FUEL_PERCENT,
            "fuelRangeKm" to FUEL_RANGE, "fuelTotalL" to FUEL_TOTAL, "evRangeKm" to EV_RANGE,
        )
        return ResolvedFidTable(
            addresses = FidMap.hybrid.associate { entry ->
                entry.field to FidAddress(entry.device, catalogFid[entry.field] ?: entry.fid)
            },
            notes = emptyList(),
            source = "test tang",
        )
    }
}
