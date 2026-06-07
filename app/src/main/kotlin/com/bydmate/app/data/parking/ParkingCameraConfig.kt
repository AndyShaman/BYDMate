package com.bydmate.app.data.parking

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class ParkingGate(
    val id: String,
    val name: String,
    val phone: String,
)

data class ParkingCamera(
    val id: String,
    val name: String,
    val url: String,
    val gates: List<ParkingGate> = emptyList(),
)

object ParkingCameraConfig {
    const val MAX_GATES_PER_CAMERA = 2

    fun defaultCamera(url: String): ParkingCamera =
        ParkingCamera(
            id = stableId("camera"),
            name = "Парковка",
            url = normalizeUrl(url) ?: url,
            gates = emptyList(),
        )

    fun decode(raw: String?, legacyUrl: String): List<ParkingCamera> {
        if (raw.isNullOrBlank()) return listOf(defaultCamera(legacyUrl))
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url").trim()
                    if (url.isBlank()) continue
                    add(
                        ParkingCamera(
                            id = obj.optString("id").ifBlank { stableId("camera_$i") },
                            name = obj.optString("name").ifBlank { "Камера ${i + 1}" },
                            url = normalizeUrl(url) ?: url,
                            gates = decodeGates(obj.optJSONArray("gates")),
                        )
                    )
                }
            }.ifEmpty { listOf(defaultCamera(legacyUrl)) }
        } catch (_: Exception) {
            listOf(defaultCamera(legacyUrl))
        }
    }

    fun encode(cameras: List<ParkingCamera>): String {
        val array = JSONArray()
        cameras.forEach { camera ->
            array.put(JSONObject().apply {
                put("id", camera.id)
                put("name", camera.name)
                put("url", camera.url)
                put("gates", JSONArray().apply {
                    camera.gates.take(MAX_GATES_PER_CAMERA).forEach { gate ->
                        put(JSONObject().apply {
                            put("id", gate.id)
                            put("name", gate.name)
                            put("phone", gate.phone)
                        })
                    }
                })
            })
        }
        return array.toString()
    }

    fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return if (withScheme.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.+"))) withScheme else null
    }

    fun newCamera(name: String = "", url: String = ""): ParkingCamera =
        ParkingCamera(
            id = uniqueId("camera"),
            name = name.ifBlank { "Новая камера" },
            url = url,
            gates = emptyList(),
        )

    fun newGate(): ParkingGate =
        ParkingGate(
            id = uniqueId("gate"),
            name = "Открыть шлагбаум",
            phone = "",
        )

    private fun decodeGates(array: JSONArray?): List<ParkingGate> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val phone = obj.optString("phone").trim()
                if (phone.isBlank()) continue
                add(
                    ParkingGate(
                        id = obj.optString("id").ifBlank { stableId("gate_$i") },
                        name = obj.optString("name").ifBlank { "Шлагбаум ${i + 1}" },
                        phone = phone,
                    )
                )
            }
        }.take(MAX_GATES_PER_CAMERA)
    }

    private fun uniqueId(prefix: String): String = "${prefix}_${UUID.randomUUID()}"

    private fun stableId(seed: String): String = seed
}
