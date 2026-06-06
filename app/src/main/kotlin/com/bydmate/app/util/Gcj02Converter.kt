package com.bydmate.app.util

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.atan2

/**
 * WGS-84 与 GCJ-02（火星坐标系）之间的坐标转换。
 *
 * 高德地图使用 GCJ-02，而 Android GPS 返回 WGS-84。
 * 数据层始终以 WGS-84 存储；仅在显示到 Amap 瓦片时做转换。
 */
object Gcj02Converter {

    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /** WGS-84 → GCJ-02（用于在高德地图上显示） */
    fun wgs84ToGcj02(lat: Double, lon: Double): Pair<Double, Double> {
        if (!isInChina(lat, lon)) return lat to lon

        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return (lat + dLat) to (lon + dLon)
    }

    /** GCJ-02 → WGS-84（逆向转换，精度约 0.00001°） */
    fun gcj02ToWgs84(lat: Double, lon: Double): Pair<Double, Double> {
        if (!isInChina(lat, lon)) return lat to lon
        val (wgsLat, wgsLon) = wgs84ToGcj02(lat, lon)
        return (lat * 2 - wgsLat) to (lon * 2 - wgsLon)
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    /** 粗略判断坐标是否在中国大陆范围内（含台湾） */
    private fun isInChina(lat: Double, lon: Double): Boolean {
        return lon in 72.004..137.8347 && lat in 0.8293..55.8271
    }
}
