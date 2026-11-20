package com.bydmate.app.data.dtc

import com.bydmate.app.helper.dtc.EcuFirmware
import com.bydmate.app.helper.dtc.EcuFirmwareWire
import org.json.JSONArray
import org.json.JSONObject

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
    fun diff(previous: FirmwareCheck?, current: FirmwareCheck): List<FirmwareRow> = current.blocks.map { b ->
        val prev = previous?.blocks?.firstOrNull { it.addr == b.addr }
        val before = EcuFirmwareWire.parse(prev?.f195Uds)
        val after = EcuFirmwareWire.parse(b.f195Uds)
        val change = when {
            after == null && before != null -> FirmwareChange.GONE
            after == null -> FirmwareChange.SILENT
            // Absent before, or silent before: nothing to compare with.
            before == null -> FirmwareChange.FIRST
            after.raw.contentEquals(before.raw) -> FirmwareChange.UNCHANGED
            else -> FirmwareChange.UPDATED
        }
        FirmwareRow(b.addr, DtcBlockNames.identify(b.addr, b.f194Uds ?: prev?.f194Uds), change, before, after)
    }

    /** [check] appended; the oldest dropped past [HISTORY]. */
    fun append(history: List<FirmwareCheck>, check: FirmwareCheck): List<FirmwareCheck> = (history + check).takeLast(HISTORY)

    /**
     * Blue «Магнитола обновилась» row: [current] blank → false; with a saved check → current differs
     * from the newest check's build; with no check → current differs from [baseline] (null → false).
     */
    fun huChanged(history: List<FirmwareCheck>, baseline: String?, current: String): Boolean {
        if (current.isBlank()) return false
        val reference = history.lastOrNull()?.huBuild ?: baseline ?: return false
        return current != reference
    }

    /** JSON of the history; null, blank or malformed → empty list, never a throw. */
    fun toJson(history: List<FirmwareCheck>): String {
        val checks = JSONArray()
        history.forEach { c ->
            val blocks = JSONArray()
            c.blocks.forEach { blocks.put(JSONObject().put("a", it.addr).putOpt("i", hex(it.f194Uds)).putOpt("f", hex(it.f195Uds))) }
            checks.put(JSONObject().put("at", c.at).put("hu", c.huBuild).put("b", blocks))
        }
        return JSONObject().put("v", 1).put("checks", checks).toString()
    }

    fun fromJson(json: String?): List<FirmwareCheck> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val checks = JSONObject(json).getJSONArray("checks")
            (0 until checks.length()).map { i ->
                val c = checks.getJSONObject(i)
                val b = c.getJSONArray("b")
                val blocks = (0 until b.length()).map { j ->
                    val e = b.getJSONObject(j)
                    FirmwareBlock(e.getInt("a"), unhex(e.optString("i")), unhex(e.optString("f")))
                }
                FirmwareCheck(c.getLong("at"), c.getString("hu"), blocks)
            }
        }.getOrDefault(emptyList())
    }

    private fun hex(b: ByteArray?): String? = b?.joinToString("") { "%02X".format(it) }

    private fun unhex(s: String): ByteArray? =
        if (s.isEmpty()) null else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
