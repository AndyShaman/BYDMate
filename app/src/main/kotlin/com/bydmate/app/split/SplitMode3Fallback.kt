package com.bydmate.app.split

/**
 * Gate for the Android 10 (DiLink 3/4) docked split-screen fallback (#385): only on SDK <= 29
 * and only after the BYD native toggle broadcast was not confirmed.
 */
object SplitMode3Fallback {
    internal const val MAX_SDK = 29

    fun applies(sdk: Int, nativeConfirmed: Boolean): Boolean = sdk <= MAX_SDK && !nativeConfirmed
}
