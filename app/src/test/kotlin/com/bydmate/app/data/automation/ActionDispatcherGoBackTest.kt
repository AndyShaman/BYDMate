package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// «Назад» is one daemon call: the system Back key, no payload, no car state touched.
class ActionDispatcherGoBackTest {
    private val helper = mockk<HelperClient>()
    private val strings = mockk<AppStrings> { every { get(any(), *anyVararg()) } returns "Не удалось нажать «Назад»" }
    private val dispatcher = testDispatcher(helper = helper, appStrings = strings)

    private val back = ActionDef(command = "", displayName = "Назад", kind = "go_back")

    @Test fun `go_back presses Back through the daemon`() = runBlocking {
        coEvery { helper.pressBack() } returns true
        assertTrue(dispatcher.dispatch(back, null).success)
        coVerify(exactly = 1) { helper.pressBack() }
    }

    @Test fun `go_back reports a failure when the daemon does not answer`() = runBlocking {
        coEvery { helper.pressBack() } returns false
        val result = dispatcher.dispatch(back, null)
        assertFalse(result.success)
        assertTrue(result.reason, !result.reason.isNullOrBlank() && !result.reason!!.contains("Unknown action kind"))
    }

    @Test fun `go_back is not speed gated`() = runBlocking {
        coEvery { helper.pressBack() } returns true
        assertTrue(dispatcher.dispatch(back, diParsData(speed = 90)).success)
    }
}
