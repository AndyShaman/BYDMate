package com.bydmate.app.hud

import android.content.Context
import android.util.Log

/** Maneuver icon PNGs for the HUD frame (f8). Donor assets: 48 files named
 *  0x<gaode-hex>.png, 38x41 px, sent verbatim - the HUD firmware scales them.
 *  All 48 total ~100 KB, kept in memory to avoid asset IO on the push loop. */
object HudIconLoader {
    private const val TAG = "HudIconLoader"

    @Volatile private var icons: Map<Int, ByteArray> = emptyMap()

    fun init(context: Context) {
        if (icons.isNotEmpty()) return
        icons = runCatching {
            context.assets.list("navi").orEmpty()
                .filter { it.startsWith("0x") && it.endsWith(".png") }
                .mapNotNull { name ->
                    val code = name.removePrefix("0x").removeSuffix(".png").toIntOrNull(16)
                        ?: return@mapNotNull null
                    val bytes = context.assets.open("navi/$name").use { it.readBytes() }
                    code to bytes
                }
                .toMap()
        }.onFailure { Log.e(TAG, "icon load failed: ${it.message}") }
            .getOrDefault(emptyMap())
        Log.i(TAG, "loaded ${icons.size} maneuver icons")
    }

    fun iconFor(gaode: Int): ByteArray? = if (gaode <= 0) null else icons[gaode]

    fun loadedCount(): Int = icons.size
}
