package com.bydmate.app.data.local

fun decideLanguage(setupCompleted: Boolean): String =
    if (setupCompleted) "ru" else "en"
