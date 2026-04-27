package com.bydmate.app.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RuleDaoPlaceUsageTest {

    private lateinit var db: AppDatabase
    private lateinit var ruleDao: RuleDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ruleDao = db.ruleDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // Helper: build a minimal RuleEntity whose triggers JSON includes one place_enter trigger
    // for the given placeId.
    private fun ruleWithPlaceTrigger(placeId: Long, name: String = "rule"): RuleEntity {
        val trigger = TriggerDef(
            param = "",
            chineseName = "",
            operator = "==",
            value = "",
            displayName = "Въезд в место",
            kind = "place_enter",
            placeId = placeId,
            placeName = "Test"
        )
        return RuleEntity(
            name = name,
            triggers = TriggerDef.listToJson(listOf(trigger)),
            actions = "[]"
        )
    }

    // Helper: rule without any place trigger.
    private fun ruleWithParamTrigger(name: String = "param_rule"): RuleEntity {
        val trigger = TriggerDef(
            param = "SOC",
            chineseName = "SOC",
            operator = "<",
            value = "20",
            displayName = "SOC < 20",
            kind = "param"
        )
        return RuleEntity(
            name = name,
            triggers = TriggerDef.listToJson(listOf(trigger)),
            actions = "[]"
        )
    }

    @Test
    fun `countRulesUsingPlace returns 0 when no rules exist`() = runTest {
        assertEquals(0, ruleDao.countRulesUsingPlace(1L))
    }

    @Test
    fun `countRulesUsingPlace returns 0 when no rules reference the place`() = runTest {
        ruleDao.insert(ruleWithParamTrigger())
        assertEquals(0, ruleDao.countRulesUsingPlace(1L))
    }

    @Test
    fun `countRulesUsingPlace returns 1 when one rule references the place`() = runTest {
        ruleDao.insert(ruleWithPlaceTrigger(placeId = 1L))
        assertEquals(1, ruleDao.countRulesUsingPlace(1L))
    }

    @Test
    fun `countRulesUsingPlace returns 0 for a different placeId`() = runTest {
        ruleDao.insert(ruleWithPlaceTrigger(placeId = 2L))
        assertEquals(0, ruleDao.countRulesUsingPlace(1L))
    }

    @Test
    fun `countRulesUsingPlace returns correct count for multiple rules using the same place`() = runTest {
        ruleDao.insert(ruleWithPlaceTrigger(placeId = 1L, name = "rule A"))
        ruleDao.insert(ruleWithPlaceTrigger(placeId = 1L, name = "rule B"))
        ruleDao.insert(ruleWithPlaceTrigger(placeId = 2L, name = "rule C"))
        ruleDao.insert(ruleWithParamTrigger(name = "rule D"))

        assertEquals(2, ruleDao.countRulesUsingPlace(1L))
        assertEquals(1, ruleDao.countRulesUsingPlace(2L))
        assertEquals(0, ruleDao.countRulesUsingPlace(3L))
    }

    @Test
    fun `countRulesUsingPlace does not match placeId as substring of larger id`() = runTest {
        // placeId=1 must NOT match a rule with placeId=11 or placeId=21
        ruleDao.insert(ruleWithPlaceTrigger(placeId = 11L, name = "rule 11"))
        ruleDao.insert(ruleWithPlaceTrigger(placeId = 21L, name = "rule 21"))

        // The LIKE pattern ends with ':1,' or ':1}' so it should NOT match 11 or 21.
        // We rely on the JSON serialization: "placeId":11 vs "placeId":1.
        assertEquals(0, ruleDao.countRulesUsingPlace(1L))
        assertEquals(1, ruleDao.countRulesUsingPlace(11L))
        assertEquals(1, ruleDao.countRulesUsingPlace(21L))
    }

    @Test
    fun `countRulesUsingPlace works for place_exit trigger kind as well`() = runTest {
        val trigger = TriggerDef(
            param = "",
            chineseName = "",
            operator = "==",
            value = "",
            displayName = "Выезд из места",
            kind = "place_exit",
            placeId = 5L,
            placeName = "Home"
        )
        val rule = RuleEntity(
            name = "exit rule",
            triggers = TriggerDef.listToJson(listOf(trigger)),
            actions = "[]"
        )
        ruleDao.insert(rule)
        assertEquals(1, ruleDao.countRulesUsingPlace(5L))
    }
}
