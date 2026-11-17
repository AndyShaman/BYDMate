package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launch of the app sets: exactly the command verified on a DiLink 3.0, nothing around it. */
class LaunchAppInWindowingModeTest {

    private val scripts = mutableListOf<Pair<String, List<String>>>()

    private fun run(
        pkg: String = "com.zhiliaoapp.musically",
        mode: Int = 3,
        component: String? = "com.zhiliaoapp.musically/com.ss.android.ugc.aweme.splash.SplashActivity",
        output: String = "Starting: Intent { cmp=... }",
    ) = launchAppInWindowingModeCore(pkg, mode, { component }, { script, args -> scripts += script to args; output })

    @Test fun `the split launch is one am start with the windowing mode and the component`() {
        assertTrue(run())
        assertEquals(
            listOf("am start --windowingMode 3 -n \"\$1\"" to
                listOf("com.zhiliaoapp.musically/com.ss.android.ugc.aweme.splash.SplashActivity")),
            scripts,
        )
    }

    @Test fun `an am error is a failed launch`() {
        assertFalse(run(output = "Error: Activity class does not exist"))
    }

    @Test fun `no component means no launch and no fallback command`() {
        assertFalse(run(component = null))
        assertTrue(scripts.isEmpty())
    }

    @Test fun `a package that is not a package name never reaches the shell`() {
        assertFalse(run(pkg = "x; reboot"))
        assertTrue(scripts.isEmpty())
    }

    @Test fun `a windowing mode outside the known range never reaches the shell`() {
        assertFalse(run(mode = 0))
        assertFalse(run(mode = 7))
        assertTrue(scripts.isEmpty())
    }
}
