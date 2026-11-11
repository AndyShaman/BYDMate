package com.bydmate.app.util

/** Default [com.bydmate.app.split.NativeSplitLauncher.systemProperty]: android.os.SystemProperties by reflection. */
internal fun readSystemProperty(name: String): String? = runCatching {
    Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java)
        .invoke(null, name) as? String
}.getOrNull()?.takeIf { it.isNotEmpty() }
