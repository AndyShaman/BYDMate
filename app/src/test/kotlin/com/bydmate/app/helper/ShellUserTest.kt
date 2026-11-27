package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [ShellUser] user-scoped shell command builders (#472).
 * User 0 commands must stay byte-identical to the existing single-user strings.
 */
class ShellUserTest {

    @Test
    fun `userIdOf extracts Android user id from uid`() {
        assertEquals(0, ShellUser.userIdOf(10180))
        assertEquals(0, ShellUser.userIdOf(2000))
        assertEquals(10, ShellUser.userIdOf(1010173))
        assertEquals(11, ShellUser.userIdOf(1110180))
    }

    @Test
    fun `pmGrant builds byte-identical command on user 0 and adds --user on secondary user`() {
        assertEquals(
            "pm grant com.bydmate.app android.permission.WRITE_SECURE_SETTINGS",
            ShellUser.pmGrant("com.bydmate.app", "android.permission.WRITE_SECURE_SETTINGS", 0),
        )
        assertEquals(
            "pm grant --user 10 com.bydmate.app android.permission.WRITE_SECURE_SETTINGS",
            ShellUser.pmGrant("com.bydmate.app", "android.permission.WRITE_SECURE_SETTINGS", 10),
        )
        assertEquals(
            "pm grant \"\$1\" android.permission.READ_LOGS",
            ShellUser.pmGrant("\"\$1\"", "android.permission.READ_LOGS", 0),
        )
        assertEquals(
            "pm grant --user 10 \"\$1\" android.permission.READ_LOGS",
            ShellUser.pmGrant("\"\$1\"", "android.permission.READ_LOGS", 10),
        )
    }

    @Test
    fun `appopsSet builds byte-identical command on user 0 and adds --user on secondary user`() {
        assertEquals(
            "appops set com.bydmate.app GET_USAGE_STATS allow",
            ShellUser.appopsSet("com.bydmate.app", "GET_USAGE_STATS", "allow", 0),
        )
        assertEquals(
            "appops set --user 10 com.bydmate.app GET_USAGE_STATS allow",
            ShellUser.appopsSet("com.bydmate.app", "GET_USAGE_STATS", "allow", 10),
        )
        assertEquals(
            "appops set \"\$1\" SYSTEM_ALERT_WINDOW allow",
            ShellUser.appopsSet("\"\$1\"", "SYSTEM_ALERT_WINDOW", "allow", 0),
        )
        assertEquals(
            "appops set --user 10 \"\$1\" SYSTEM_ALERT_WINDOW allow",
            ShellUser.appopsSet("\"\$1\"", "SYSTEM_ALERT_WINDOW", "allow", 10),
        )
        assertEquals(
            "appops set \"\$1\" PROJECT_MEDIA allow",
            ShellUser.appopsSet("\"\$1\"", "PROJECT_MEDIA", "allow", 0),
        )
        assertEquals(
            "appops set --user 10 \"\$1\" PROJECT_MEDIA allow",
            ShellUser.appopsSet("\"\$1\"", "PROJECT_MEDIA", "allow", 10),
        )
    }

    @Test
    fun `settingsSecure, notificationAllowListener and amBroadcast handle user 0 and secondary user`() {
        assertEquals(
            "settings put secure enabled_accessibility_services \"\$1\"",
            ShellUser.settingsSecure("put", "enabled_accessibility_services \"\$1\"", 0),
        )
        assertEquals(
            "settings --user 10 put secure enabled_accessibility_services \"\$1\"",
            ShellUser.settingsSecure("put", "enabled_accessibility_services \"\$1\"", 10),
        )
        assertEquals(
            "settings get secure \"\$1\"",
            ShellUser.settingsSecure("get", "\"\$1\"", 0),
        )
        assertEquals(
            "settings --user 10 get secure \"\$1\"",
            ShellUser.settingsSecure("get", "\"\$1\"", 10),
        )

        assertEquals(
            "cmd notification allow_listener \"\$1\"",
            ShellUser.notificationAllowListener("\"\$1\"", 0),
        )
        assertEquals(
            "cmd notification allow_listener \"\$1\" 10",
            ShellUser.notificationAllowListener("\"\$1\"", 10),
        )

        val recoverArgs =
            "--include-stopped-packages --receiver-foreground -a com.bydmate.app.action.RECOVER_START -n \"\$1\""
        assertEquals(
            "am broadcast $recoverArgs",
            ShellUser.amBroadcast(recoverArgs, 0),
        )
        assertEquals(
            "am broadcast --user 10 $recoverArgs",
            ShellUser.amBroadcast(recoverArgs, 10),
        )
    }
}
