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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DriveMode used to offer "0" as NORMAL; live on Leopard 3 the fid reports 3 for NORMAL and
 * 0 only mid-switch, so rules saved against "0" can never match again. The one-shot migration
 * rewrites exactly those trigger values and leaves everything else byte for byte.
 *
 * Uses a real in-memory Room DB so the withTransaction wrapper is exercised for real, and so
 * "left untouched" can be asserted against what is actually on disk.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DriveModeRuleMigrationTest {

    private lateinit var db: AppDatabase
    private lateinit var ruleDao: RuleDao
    private val settings: SettingsRepository = mockk()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        ruleDao = db.ruleDao()
        coEvery { settings.isDriveModeRuleMigrationDone() } returns false
        coEvery { settings.setDriveModeRuleMigrationDone() } returns Unit
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun trigger(param: String, value: String, op: String = "==") = TriggerDef(
        param = param, chineseName = "", operator = op, value = value, displayName = param,
    )

    private suspend fun insert(vararg triggers: TriggerDef): Long =
        ruleDao.insert(RuleEntity(name = "rule", triggers = TriggerDef.listToJson(triggers.toList()), actions = "[]"))

    private suspend fun triggersOf(id: Long): List<TriggerDef> =
        TriggerDef.listFromJson(ruleDao.getById(id)!!.triggers)

    private fun migration(dao: RuleDao = ruleDao) = DriveModeRuleMigration(dao, settings, db)

    @Test fun `rewrites the legacy DriveMode value to NORMAL`() = runTest {
        val id = insert(trigger("DriveMode", "0"))

        assertEquals(1, migration().runOnce())

        val triggers = triggersOf(id)
        assertEquals("3", triggers.single().value)
        assertEquals("DriveMode", triggers.single().param)
        coVerify(exactly = 1) { settings.setDriveModeRuleMigrationDone() }
    }

    /** The legacy code is dead whatever the operator compares it with, so any DriveMode
     *  trigger holding "0" is rewritten; other params and other values stay as they are. */
    @Test fun `leaves other params and other DriveMode values untouched`() = runTest {
        val soc = insert(trigger("SOC", "0"))
        val offRoad = insert(trigger("DriveMode", "4"))
        val negated = insert(trigger("DriveMode", "0", op = "!="))

        assertEquals("only the DriveMode rule holding the legacy code may be rewritten", 1, migration().runOnce())

        assertEquals("0", triggersOf(soc).single().value)
        assertEquals("4", triggersOf(offRoad).single().value)
        assertEquals("3", triggersOf(negated).single().value)
        assertEquals("!=", triggersOf(negated).single().operator)
    }

    @Test fun `keeps the sibling triggers of a migrated rule intact`() = runTest {
        val id = insert(trigger("Speed", "0", op = ">"), trigger("DriveMode", "0"))

        migration().runOnce()

        val triggers = triggersOf(id)
        assertEquals(2, triggers.size)
        assertEquals("Speed", triggers[0].param)
        assertEquals("0", triggers[0].value)
        assertEquals(">", triggers[0].operator)
        assertEquals("3", triggers[1].value)
    }

    @Test fun `is a no-op once the flag is set`() = runTest {
        coEvery { settings.isDriveModeRuleMigrationDone() } returns true
        val id = insert(trigger("DriveMode", "0"))

        assertEquals(0, migration().runOnce())

        assertEquals("0", triggersOf(id).single().value)
        coVerify(exactly = 0) { settings.setDriveModeRuleMigrationDone() }
    }

    @Test fun `marks itself done even when nothing matched`() = runTest {
        val id = insert(trigger("SOC", "20"))

        assertEquals(0, migration().runOnce())

        assertEquals("20", triggersOf(id).single().value)
        coVerify(exactly = 1) { settings.setDriveModeRuleMigrationDone() }
    }

    /** An unreadable trigger list must be skipped, never written back as "[]". */
    @Test fun `malformed JSON is neither rewritten nor blanked`() = runTest {
        val broken = "{this is not a trigger list"
        val id = ruleDao.insert(RuleEntity(name = "broken", triggers = broken, actions = "[]"))
        val legacy = insert(trigger("DriveMode", "0"))

        assertEquals("the broken rule may not be counted as migrated", 1, migration().runOnce())

        assertEquals("the original JSON must stay byte for byte", broken, ruleDao.getById(id)!!.triggers)
        assertEquals("and the healthy rule is still migrated", "3", triggersOf(legacy).single().value)
    }

    /** A DB failure must not set the flag: the rules are still dead and deserve a retry. */
    @Test fun `a failure leaves the flag unset for the next start`() = runTest {
        val failing: RuleDao = mockk()
        coEvery { failing.getAllList() } throws IllegalStateException("db locked")

        assertEquals(0, migration(failing).runOnce())

        coVerify(exactly = 0) { settings.setDriveModeRuleMigrationDone() }
    }
}
