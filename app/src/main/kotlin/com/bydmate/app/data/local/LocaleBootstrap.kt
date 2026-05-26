package com.bydmate.app.data.local

import java.util.Locale

fun decideLanguage(setupCompleted: Boolean): String =
    when (Locale.getDefault().language) {
        "zh" -> "zh"
        "ru" -> "ru"
        else -> "en"
    }
