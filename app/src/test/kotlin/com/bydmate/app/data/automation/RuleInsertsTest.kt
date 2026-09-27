package com.bydmate.app.data.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.RuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The rule limit on a real Room database, bound to it like the app binds its RuleDao. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RuleInsertsTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        RuleInserts.bind(db.ruleDao(), db)
    }

    @After fun tearDown() {
        db.close()
    }

    private fun rule(n: Int) = RuleEntity(name = "r$n", triggers = "[]", actions = "[]")

    @Test fun `two inserts racing at 49 rules leave 50`() = runBlocking {
        repeat(LIMIT - 1) { db.ruleDao().insert(rule(it)) }

        val ids = List(2) { n -> async(Dispatchers.IO) { RuleInserts.insertWithinLimit(db.ruleDao(), rule(100 + n), LIMIT) } }
            .awaitAll()

        assertEquals(1, ids.count { it == null })
        assertEquals(LIMIT, db.ruleDao().getCount())
    }

    @Test fun `a full table takes no insert`() = runBlocking {
        repeat(LIMIT) { db.ruleDao().insert(rule(it)) }

        assertNull(RuleInserts.insertWithinLimit(db.ruleDao(), rule(100), LIMIT))
        assertEquals(LIMIT, db.ruleDao().getCount())
    }

    @Test fun `two templates racing an agent insert at 48 rules never pass 50`() = runBlocking {
        repeat(LIMIT - 2) { db.ruleDao().insert(rule(it)) }

        val templates = async(Dispatchers.IO) {
            RuleInserts.insertAllWithinLimit(db.ruleDao(), listOf(rule(200), rule(201)), LIMIT)
        }
        val agent = async(Dispatchers.IO) { RuleInserts.insertWithinLimit(db.ruleDao(), rule(300), LIMIT) }
        val templateIds = templates.await()
        val agentId = agent.await()

        // Whichever went first: both templates and no agent rule (50), or the agent rule and no template (49).
        if (templateIds != null) {
            assertEquals(2, templateIds.size)
            assertNull(agentId)
            assertEquals(LIMIT, db.ruleDao().getCount())
        } else {
            assertEquals(LIMIT - 1, db.ruleDao().getCount())
        }
    }

    @Test fun `two templates at 49 rules go in not at all`() = runBlocking {
        repeat(LIMIT - 1) { db.ruleDao().insert(rule(it)) }

        assertNull(RuleInserts.insertAllWithinLimit(db.ruleDao(), listOf(rule(200), rule(201)), LIMIT))
        assertEquals(LIMIT - 1, db.ruleDao().getCount())
    }

    private companion object {
        const val LIMIT = 50
    }
}
