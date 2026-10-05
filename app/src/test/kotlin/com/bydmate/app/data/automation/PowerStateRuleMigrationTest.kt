package com.bydmate.app.data.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The «Питание» condition is gone: the app starts after the car is powered, so ON never fired,
 * OFF cannot fire and DRIVE fired only by race. Saved rules move to «Запуск BYDMate» (ON and
 * DRIVE; «Шторка при движении» alone to «Передача = D»); a rule that needed the car off is removed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PowerStateRuleMigrationTest {

    private lateinit var db: AppDatabase
    private lateinit var ruleDao: RuleDao
    private val settings: SettingsRepository = mockk()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val labels = PowerStateRuleMigration.Labels(serviceStart = "Запуск BYDMate", gearDrive = "Передача = D")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        ruleDao = db.ruleDao()
        coEvery { settings.isPowerStateRuleMigrationDone() } returns false
        coEvery { settings.setPowerStateRuleMigrationDone() } returns Unit
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun param(param: String, value: String, op: String = "==") = TriggerDef(
        param = param, chineseName = "", operator = op, value = value, displayName = param,
    )

    private fun power(value: String, op: String = "==") = param("PowerState", value, op)

    private val serviceStart = TriggerDef(
        param = "ServiceStart", chineseName = "服务启动", operator = "==", value = "true",
        displayName = "Запуск BYDMate", kind = "service_start",
    )

    private fun convert(logic: String, vararg triggers: TriggerDef) =
        PowerStateRuleMigration.convert(logic, triggers.toList(), labels, driveIsGear = false)

    private fun convertSunshade(logic: String, vararg triggers: TriggerDef) =
        PowerStateRuleMigration.convert(logic, triggers.toList(), labels, driveIsGear = true)

    // --- the pure conversion ---

    @Test fun `ON becomes the BYDMate start trigger`() {
        val out = convert("AND", power("1"))!!.single()

        assertEquals("service_start", out.kind)
        assertEquals("ServiceStart", out.param)
        assertEquals("true", out.value)
        assertEquals("Запуск BYDMate", out.displayName)
    }

    @Test fun `ON next to an existing start trigger is just dropped`() {
        val out = convert("AND", serviceStart, power("1"))!!

        assertEquals(listOf(serviceStart), out)
    }

    @Test fun `DRIVE becomes the BYDMate start trigger`() {
        val out = convert("AND", power("2"))!!.single()

        assertEquals("service_start", out.kind)
        assertEquals("Запуск BYDMate", out.displayName)
    }

    @Test fun `DRIVE next to an existing start trigger is just dropped`() {
        assertEquals(listOf(serviceStart), convert("AND", serviceStart, power("2")))
    }

    @Test fun `ON and DRIVE in one rule give one start trigger`() {
        assertEquals(listOf("service_start"), convert("AND", power("1"), power("2"))!!.map { it.kind })
    }

    @Test fun `DRIVE in the sunshade template becomes gear D`() {
        val out = convertSunshade("AND", power("2"))!!.single()

        assertEquals("param", out.kind)
        assertEquals("Gear", out.param)
        assertEquals("档位", out.chineseName)
        assertEquals("==", out.operator)
        assertEquals("4", out.value)
        assertEquals("Передача = D", out.displayName)
    }

    @Test fun `winter start keeps its temperature and gets the BYDMate start`() {
        val out = convert("AND", param("ExtTemp", "0", "<"), power("2"))!!

        assertEquals(listOf("ExtTemp", "ServiceStart"), out.map { it.param })
        assertEquals(listOf("param", "service_start"), out.map { it.kind })
        assertEquals("0", out[0].value)
    }

    @Test fun `summer cooling keeps its cabin temperature and gets the BYDMate start`() {
        val out = convert("AND", param("InsideTemp", "30", ">"), power("2"))!!

        assertEquals(listOf("InsideTemp", "ServiceStart"), out.map { it.param })
        assertEquals("30", out[0].value)
    }

    @Test fun `only the sunshade template names count, in every language`() {
        for (name in listOf("Шторка при движении", "Sunshade while driving", "行驶开遮阳帘", " Шторка при движении ")) {
            assertEquals(name, true, PowerStateRuleMigration.isSunshadeTemplate(name))
        }
        for (name in listOf("Летнее охлаждение", "Зимний старт", "Шторка", "")) {
            assertEquals(name, false, PowerStateRuleMigration.isSunshadeTemplate(name))
        }
    }

    @Test fun `DRIVE in the sunshade template next to an existing gear D is just dropped`() {
        val gearD = param("Gear", "4")
        val out = convertSunshade("AND", gearD, power("2"))!!

        assertEquals(listOf(gearD), out)
    }

    @Test fun `DRIVE in the sunshade template next to another gear condition still adds gear D`() {
        val notP = param("Gear", "1", "!=")
        val out = convertSunshade("AND", notP, power("2"))!!

        assertEquals(listOf("1", "4"), out.map { it.value })
    }

    @Test fun `OFF in an AND rule deletes the rule`() {
        assertNull(convert("AND", param("SOC", "20", "<"), power("0")))
    }

    @Test fun `a PowerState condition that cannot hold with the car on deletes an AND rule`() {
        assertNull(convert("AND", power("3")))
        assertNull(convert("AND", power("abc")))
        assertNull(convert("AND", power("1", "~")))
        assertNull(convert("AND", power("0")))
    }

    @Test fun `a PowerState condition that can hold with the car on becomes the start trigger`() {
        val soc = param("SOC", "20", "<")
        for ((op, v) in listOf("!=" to "0", "!=" to "1", "!=" to "2", ">" to "0", ">=" to "1", "<" to "2", "<=" to "1")) {
            val out = convert("AND", soc, power(v, op))!!
            assertEquals("$op $v", listOf("param", "service_start"), out.map { it.kind })
        }
    }

    @Test fun `a not-OFF condition in the sunshade template becomes the start trigger, not gear D`() {
        val out = convertSunshade("AND", power("0", "!="))!!.single()

        assertEquals("service_start", out.kind)
    }

    @Test fun `DRIVE in the sunshade template still becomes gear D`() {
        assertEquals("Gear", convertSunshade("AND", power("2")).orEmpty().single().param)
    }

    @Test fun `an OR rule with a below-ON condition drops the trigger`() {
        val soc = param("SOC", "20", "<")

        assertEquals(listOf(soc), convert("OR", soc, power("1", "<")))
    }

    @Test fun `OFF in an OR rule is dropped and the other trigger stays`() {
        val soc = param("SOC", "20", "<")

        assertEquals(listOf(soc), convert("OR", soc, power("0")))
    }

    @Test fun `an OR rule with only OFF is deleted`() {
        assertNull(convert("OR", power("0")))
    }

    @Test fun `ON in an OR rule becomes the start trigger beside the others`() {
        val soc = param("SOC", "20", "<")
        val out = convert("OR", soc, power("1"))!!

        assertEquals(listOf("param", "service_start"), out.map { it.kind })
    }

    @Test fun `ON in a one-shot rule is dropped, an event cannot join a one-shot moment`() {
        val once = TriggerDef(
            param = "OnceAt", chineseName = "", operator = "==", value = "2026-10-06T08:00",
            displayName = "once", kind = OneShotTrigger.KIND,
        )

        assertEquals(listOf(once), convert("AND", once, power("1")))
    }

    @Test fun `a rule without PowerState comes back as it is`() {
        val triggers = listOf(param("Speed", "100", ">"), serviceStart)

        assertSame(triggers, PowerStateRuleMigration.convert("AND", triggers, labels, driveIsGear = false))
    }

    // --- the one-shot pass over saved rules ---

    private suspend fun insert(logic: String, vararg triggers: TriggerDef): Long = insertNamed("rule", logic, *triggers)

    private suspend fun insertNamed(name: String, logic: String, vararg triggers: TriggerDef): Long =
        ruleDao.insert(
            RuleEntity(
                name = name, triggerLogic = logic, triggers = TriggerDef.listToJson(triggers.toList()),
                actions = """[{"command":"车窗关闭","displayName":"x","kind":"param"}]""",
                enabled = true, cooldownSeconds = 123, fireOncePerTrip = true, requirePark = true,
            )
        )

    private fun migration() = PowerStateRuleMigration(ruleDao, settings, db, context)

    @Test fun `saved rules are converted or removed, everything else of a rule stays`() = runTest {
        val music = insert("AND", power("1"))
        val winter = insert("AND", param("ExtTemp", "0", "<"), power("2"))
        val summer = insertNamed("Летнее охлаждение", "AND", param("InsideTemp", "30", ">"), power("2"))
        val sunshade = insertNamed("Шторка при движении", "AND", power("2"))
        val offOnly = insert("AND", param("SOC", "20", "<"), power("0"))
        val untouched = insert("AND", param("Speed", "100", ">"))
        val before = ruleDao.getById(winter)!!

        assertEquals(5, migration().runOnce())

        assertEquals("service_start", TriggerDef.listFromJson(ruleDao.getById(music)!!.triggers).single().kind)
        val after = ruleDao.getById(winter)!!
        assertEquals(listOf("ExtTemp", "ServiceStart"), TriggerDef.listFromJson(after.triggers).map { it.param })
        assertEquals(before.copy(triggers = after.triggers), after)
        assertEquals(listOf("param", "service_start"), TriggerDef.listFromJson(ruleDao.getById(summer)!!.triggers).map { it.kind })
        val gear = TriggerDef.listFromJson(ruleDao.getById(sunshade)!!.triggers).single()
        assertEquals("Gear", gear.param)
        assertEquals("4", gear.value)
        assertNull(ruleDao.getById(offOnly))
        assertEquals("Speed", TriggerDef.listFromJson(ruleDao.getById(untouched)!!.triggers).single().param)
        coVerify(exactly = 1) { settings.setPowerStateRuleMigrationDone() }
    }

    @Test fun `an empty database is fine and still marks the pass done`() = runTest {
        assertEquals(0, migration().runOnce())

        coVerify(exactly = 1) { settings.setPowerStateRuleMigrationDone() }
    }

    @Test fun `runs once`() = runTest {
        coEvery { settings.isPowerStateRuleMigrationDone() } returns true
        val id = insert("AND", power("2"))

        assertEquals(0, migration().runOnce())

        assertEquals("PowerState", TriggerDef.listFromJson(ruleDao.getById(id)!!.triggers).single().param)
    }

    @Test fun `a second run finds nothing left to change`() = runTest {
        insert("AND", power("2"))
        assertEquals(1, migration().runOnce())

        assertEquals(0, migration().runOnce())
    }
}
