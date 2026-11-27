package com.bydmate.app.helper

/**
 * User-scoped shell command builders for multi-user Android head units (e.g. Android 14 AAOS
 * running apps under user 10 while adbd and the helper daemon run under user 0, #472).
 *
 * User 0 commands stay byte-identical to the legacy single-user strings so existing cars see no
 * behavioral change.
 */
internal object ShellUser {
    private const val PER_USER_RANGE = 100_000

    /** Extracts the Android user id from a process [uid] (mirrors UserHandle.getUserId). */
    fun userIdOf(uid: Int): Int = if (uid <= 0) 0 else uid / PER_USER_RANGE

    fun pmGrant(target: String, permission: String, userId: Int): String =
        if (userId > 0) "pm grant --user $userId $target $permission"
        else "pm grant $target $permission"

    fun appopsSet(target: String, op: String, mode: String, userId: Int): String =
        if (userId > 0) "appops set --user $userId $target $op $mode"
        else "appops set $target $op $mode"

    fun settingsSecure(verb: String, args: String, userId: Int): String =
        if (userId > 0) "settings --user $userId $verb secure $args"
        else "settings $verb secure $args"

    fun notificationAllowListener(component: String, userId: Int): String =
        if (userId > 0) "cmd notification allow_listener $component $userId"
        else "cmd notification allow_listener $component"

    fun amBroadcast(args: String, userId: Int): String =
        if (userId > 0) "am broadcast --user $userId $args"
        else "am broadcast $args"
}
