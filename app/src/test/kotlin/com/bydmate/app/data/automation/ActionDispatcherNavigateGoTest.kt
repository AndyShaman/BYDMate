package com.bydmate.app.data.automation

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `go` as it arrives in the navigate payload from the agent and from an automation rule. */
class ActionDispatcherNavigateGoTest {

    @Test
    fun `go true means start guidance`() {
        assertTrue(
            ActionDispatcher.autoGoRequested(JSONObject("""{"lat":53.9,"lon":27.55,"go":true}"""))
        )
    }

    @Test
    fun `absent or false go only builds the route`() {
        assertFalse(ActionDispatcher.autoGoRequested(JSONObject("""{"lat":53.9,"lon":27.55}""")))
        assertFalse(
            ActionDispatcher.autoGoRequested(JSONObject("""{"shortcut":"home","go":false}"""))
        )
    }

    @Test
    fun `show mode ignores go`() {
        assertFalse(
            ActionDispatcher.autoGoRequested(
                JSONObject("""{"lat":53.9,"lon":27.55,"show":true,"go":true}""")
            )
        )
    }
}
