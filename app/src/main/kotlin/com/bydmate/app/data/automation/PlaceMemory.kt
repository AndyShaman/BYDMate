package com.bydmate.app.data.automation

import android.content.Context
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
import java.util.concurrent.ConcurrentHashMap

/**
 * What the place conditions of each rule answered at the last GPS point (#418).
 *
 * Place triggers need a GPS point. Without one each keeps its last known answer (false before
 * the first point), so the other conditions of the rule go on working and a GPS gap does not
 * look like leaving the place. The answers are kept on disk too: the car does not move while
 * it is off, so after a restart a late first point is compared with where the car stood
 * instead of being taken as the starting state.
 */
internal class PlaceMemory(context: Context) {

    private data class Memo(val triggersHash: Int, val results: List<Boolean>) {
        fun encode(): String = "$triggersHash:" + results.joinToString("") { if (it) "1" else "0" }
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val memos = ConcurrentHashMap<Long, Memo>()
    private var loaded = false

    /** Keeps the state of the rules in [activeIds] only; the first call reads what was saved. */
    fun retain(activeIds: Set<Long>) {
        if (!loaded) {
            loaded = true
            val stale = prefs.edit()
            for ((key, value) in prefs.all) {
                val id = key.toLongOrNull()
                val memo = (value as? String)?.let { decode(it) }
                if (id != null && id in activeIds && memo != null) memos[id] = memo else stale.remove(key)
            }
            stale.apply()
        }
        memos.keys.retainAll(activeIds)
    }

    /**
     * Returns the answers to use on this tick and whether the rule matches only because of its
     * first GPS point: the car did not enter or leave anything, it was started there.
     * [combine] is the rule's own AND / OR over the answers.
     */
    fun resolve(
        rule: RuleEntity,
        triggers: List<TriggerDef>,
        hasFix: Boolean,
        perTrigger: List<Boolean>,
        combine: (List<Boolean>) -> Boolean,
    ): Pair<List<Boolean>, Boolean> {
        val isPlace = triggers.map { it.kind in RuleShare.PLACE_KINDS }
        if (isPlace.none { it }) return perTrigger to false
        // An edited rule starts over: its saved answers belong to other conditions.
        val triggersHash = (rule.triggers + rule.triggerLogic).hashCode()
        val memo = memos[rule.id]?.takeIf { it.triggersHash == triggersHash }
        if (!hasFix) {
            if (memo == null) return perTrigger to false
            return perTrigger.mapIndexed { i, r -> if (isPlace[i]) memo.results.getOrElse(i) { false } else r } to false
        }
        // Only the place slots are kept, so a speed or SOC condition changing does not rewrite the file.
        val now = Memo(triggersHash, perTrigger.mapIndexed { i, r -> r && isPlace[i] })
        memos[rule.id] = now
        if (now != memo) prefs.edit().putString(rule.id.toString(), now.encode()).apply()
        if (memo != null) return perTrigger to false
        val withoutPlaces = perTrigger.mapIndexed { i, r -> r && !isPlace[i] }
        return perTrigger to (combine(perTrigger) && !combine(withoutPlaces))
    }

    private fun decode(text: String): Memo? {
        val hash = text.substringBefore(':').toIntOrNull() ?: return null
        return Memo(hash, text.substringAfter(':', "").map { it == '1' })
    }

    private companion object {
        const val PREFS = "automation_place_state"
    }
}
