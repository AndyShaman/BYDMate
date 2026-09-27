package com.bydmate.app.data.automation

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.entity.RuleEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The one way a rule is added (editor, copy, «Сохранить как новое», import, voice agent), so the
 * rule limit holds when two of them race: a count checked and an insert made apart let two copies
 * made at 49 rules both in. Count and insert run behind one lock, inside one database transaction.
 */
object RuleInserts {

    /** The lock of one rule table and the database its transaction runs on. */
    private class Binding {
        val lock = Mutex()
        @Volatile var db: WeakReference<RoomDatabase>? = null
    }

    // Keyed by the DAO: the app has a single RuleDao (Room caches it per database), so every
    // insert path shares one lock; a test's own DAO gets its own, unbound one.
    private val bindings = WeakHashMap<RuleDao, Binding>()

    /** Ties [ruleDao] to the [db] it came from, so its count and insert share a transaction. */
    fun bind(ruleDao: RuleDao, db: RoomDatabase) {
        bindingOf(ruleDao).db = WeakReference(db)
    }

    /** Inserts [rule] unless [limit] rules exist already. The new row id, or null when full. */
    suspend fun insertWithinLimit(ruleDao: RuleDao, rule: RuleEntity, limit: Int): Long? {
        val binding = bindingOf(ruleDao)
        return binding.lock.withLock {
            val db = binding.db?.get()
            if (db == null) countAndInsert(ruleDao, rule, limit)
            else db.withTransaction { countAndInsert(ruleDao, rule, limit) }
        }
    }

    private suspend fun countAndInsert(ruleDao: RuleDao, rule: RuleEntity, limit: Int): Long? =
        if (ruleDao.getCount() >= limit) null else ruleDao.insert(rule)

    private fun bindingOf(ruleDao: RuleDao): Binding = synchronized(bindings) { bindings.getOrPut(ruleDao) { Binding() } }
}
