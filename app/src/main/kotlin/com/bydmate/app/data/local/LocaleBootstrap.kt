package com.bydmate.app.data.local

// Default app language is Russian: this project is built primarily for Russian
// speakers, and DiLink head units ship with zh/en system locales (no Russian),
// so deriving the default from Locale.getDefault() would mislocalize the core
// audience. Existing users keep Russian; new installs get English; Chinese/other
// users switch manually in Settings (zh option added in PR #39).
fun decideLanguage(setupCompleted: Boolean): String =
    if (setupCompleted) "ru" else "en"
