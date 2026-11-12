package com.bydmate.app.data.automation

import android.content.Context
import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.RuleLogDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.service.TrackingService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #418: a place-enter rule opened the gates when the car was started already inside the
 * place. After a cold start the location is null on the first ticks; the first GPS point
 * must only set the inside/outside state, the rule fires on a later outside -> inside.
 */
// SDK 33: on lower levels Robolectric rejects ContextCompat.registerReceiver thrown from the engine's init.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutomationEnginePlaceStartupTest {

    private val place = PlaceEntity(id = 7, name = "Двор", lat = 53.9, lon = 27.56, radiusM = 100)

    @Suppress("UNCHECKED_CAST")
    private val locationFlow: MutableStateFlow<Location?> =
        TrackingService::class.java.getDeclaredField("_lastLocation")
            .apply { isAccessible = true }
            .get(null) as MutableStateFlow<Location?>

    @After fun resetLocation() {
        locationFlow.value = null
    }

    private fun at(lat: Double, lon: Double) = Location("test").apply {
        latitude = lat
        longitude = lon
    }

    private val inside get() = at(place.lat, place.lon)
    private val outside get() = at(place.lat + 0.05, place.lon)

    private fun placeRule(kind: String) = RuleEntity(
        id = 1, name = "gates",
        triggerLogic = "AND",
        triggers = TriggerDef.listToJson(
            listOf(
                TriggerDef(
                    param = "Place", chineseName = "位置", operator = "==", value = "enter",
                    displayName = kind, kind = kind, placeId = place.id, placeName = place.name,
                )
            )
        ),
        actions = ActionDef.listToJson(listOf(ActionDef("notify", "n", "notification"))),
        cooldownSeconds = 0,
    )

    /** «Въезд в место» OR «скорость > 5»: the speed condition must not wait for GPS. */
    private fun placeOrSpeedRule() = placeRule("place_enter").let { rule ->
        rule.copy(
            triggerLogic = "OR",
            triggers = TriggerDef.listToJson(
                TriggerDef.listFromJson(rule.triggers) + TriggerDef("Speed", "车速", ">", "5", "speed")
            ),
        )
    }

    private fun setup(kind: String = "place_enter"): Pair<AutomationEngine, RuleDao> = setup(placeRule(kind))

    private fun setup(rule: RuleEntity): Pair<AutomationEngine, RuleDao> {
        val ruleDao = mockk<RuleDao>(relaxed = true) {
            coEvery { getEnabled() } returns listOf(rule)
        }
        val engine = AutomationEngine(
            ruleDao = ruleDao,
            ruleLogDao = mockk<RuleLogDao>(relaxed = true),
            actionDispatcher = mockk(relaxed = true),
            placeRepository = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns listOf(place) },
            networkAvailableMonitor = mockk<NetworkAvailableMonitor> {
                every { lastAvailableAt } returns 0L
                every { probePending } returns false
            },
            context = ApplicationProvider.getApplicationContext<Context>(),
            appStrings = com.bydmate.app.util.AppStrings(ApplicationProvider.getApplicationContext()),
        )
        engine.interactiveProvider = { true }
        return engine to ruleDao
    }

    private suspend fun AutomationEngine.tick(location: Location?, speed: Int? = null) {
        locationFlow.value = location
        evaluate(diParsData(soc = 50, speed = speed), null)
    }

    @Test fun `start-up inside the place does not fire on the first GPS point`() = runBlocking {
        val (engine, ruleDao) = setup()

        engine.tick(null)    // cold start: no fix yet
        engine.tick(inside)  // first fix: the car already stands in the yard

        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
    }

    @Test fun `after a start inside, leaving and coming back fires once`() = runBlocking {
        val (engine, ruleDao) = setup()

        engine.tick(null)
        engine.tick(inside)
        engine.tick(outside)
        engine.tick(inside)
        engine.tick(inside)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `start-up outside, then entering fires once`() = runBlocking {
        val (engine, ruleDao) = setup()

        engine.tick(null)
        engine.tick(outside)
        engine.tick(inside)
        engine.tick(inside)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `no GPS point yet never fires`() = runBlocking {
        val (engine, ruleDao) = setup()

        engine.tick(null)
        engine.tick(null)
        engine.tick(null)

        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
    }

    @Test fun `start-up outside the place does not fire a leave rule on the first GPS point`() = runBlocking {
        val (engine, ruleDao) = setup("place_exit")

        engine.tick(null)     // cold start: no fix yet
        engine.tick(outside)  // first fix: the car stands outside, it did not leave anything

        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
    }

    @Test fun `after a start outside, entering and leaving fires a leave rule once`() = runBlocking {
        val (engine, ruleDao) = setup("place_exit")

        engine.tick(null)
        engine.tick(outside)
        engine.tick(inside)
        engine.tick(outside)
        engine.tick(outside)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `a place-or-speed rule fires on the speed front before the first GPS point`() = runBlocking {
        val (engine, ruleDao) = setup(placeOrSpeedRule())

        engine.tick(null, speed = 0)   // underground parking: no fix yet
        engine.tick(null, speed = 10)  // the car moves off, still no fix

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }

    @Test fun `a place-or-speed rule does not fire when the first GPS point is inside the place`() = runBlocking {
        val (engine, ruleDao) = setup(placeOrSpeedRule())

        engine.tick(null, speed = 0)
        engine.tick(inside, speed = 0)

        coVerify(exactly = 0) { ruleDao.updateLastTriggered(any(), any()) }
    }

    @Test fun `a leave rule fires when GPS comes back outside after a gap`() = runBlocking {
        val (engine, ruleDao) = setup("place_exit")

        engine.tick(inside)
        engine.tick(null)     // GPS lost under a roof: the car is still counted as inside
        engine.tick(outside)

        coVerify(exactly = 1) { ruleDao.updateLastTriggered(1, any()) }
    }
}
