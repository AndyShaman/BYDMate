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
 * The Trunk catalog offered "0" as closed, but the tailgate fid answers 2 closed, 1 open,
 * 3 moving: a saved «Багажник = Закрыт» never matched. The one-shot migration rewrites exactly
 * those values, once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TrunkRuleMigrationTest {

    private lateinit var db: AppDatabase
    private lateinit var ruleDao: RuleDao
    private val settings: SettingsRepository = mockk()

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        ruleDao = db.ruleDao()
        coEvery { settings.isTrunkRuleMigrationDone() } returns false
        coEvery { settings.setTrunkRuleMigrationDone() } returns Unit
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

    private fun migration() = TrunkRuleMigration(ruleDao, settings, db)

    @Test fun `rewrites the legacy closed Trunk value to 2`() = runTest {
        val closed = insert(trigger("Trunk", "0"), trigger("SOC", "0", "<"))
        val notClosed = insert(trigger("Trunk", "0", "!="))

        assertEquals(2, migration().runOnce())

        assertEquals(listOf("2", "0"), triggersOf(closed).map { it.value })
        assertEquals("!=", triggersOf(notClosed).single().operator)
        assertEquals("2", triggersOf(notClosed).single().value)
        coVerify(exactly = 1) { settings.setTrunkRuleMigrationDone() }
    }

    @Test fun `leaves open Trunk and other params untouched`() = runTest {
        val open = insert(trigger("Trunk", "1"))
        val hood = insert(trigger("Hood", "0"))

        assertEquals(0, migration().runOnce())

        assertEquals("1", triggersOf(open).single().value)
        assertEquals("0", triggersOf(hood).single().value)
    }

    @Test fun `runs once`() = runTest {
        coEvery { settings.isTrunkRuleMigrationDone() } returns true
        val id = insert(trigger("Trunk", "0"))

        assertEquals(0, migration().runOnce())

        assertEquals("0", triggersOf(id).single().value)
    }

    @Test fun `an old share file gets the same fix`() {
        val fixed = TrunkRuleMigration.fix(listOf(trigger("Trunk", "0"), trigger("Trunk", "1")))

        assertEquals(listOf("2", "1"), fixed.map { it.value })
    }
}
