package com.bydmate.app.ui.automation

import com.bydmate.app.data.local.entity.RuleEntity

/**
 * The order the driver dragged the rules into (#249). Stored as a comma-separated list of rule
 * ids; a rule missing from it (created, duplicated or imported after the last drag) goes on top,
 * newest first, which is how the list looked before any drag. Ids of deleted rules are skipped.
 */
internal object RuleOrder {

    fun parse(raw: String): List<Long> =
        raw.split(',').mapNotNull { it.trim().toLongOrNull() }.distinct()

    fun serialize(ids: List<Long>): String = ids.joinToString(",")

    /** [rules] in the [saved] order; [rules] come newest first, as the DAO gives them. */
    fun apply(saved: List<Long>, rules: List<RuleEntity>): List<RuleEntity> {
        val byId = rules.associateBy { it.id }
        val placed = saved.mapNotNull { byId[it] }
        val placedIds = placed.mapTo(HashSet()) { it.id }
        return rules.filterNot { it.id in placedIds } + placed
    }

    /**
     * Drops [moved] into the slot [target] occupies, pushing the rest along: the plain
     * drag-and-drop insert, not a swap, so the items between the two keep their relative order.
     * Under a filter [target] is the shown rule whose place was taken, so the shown rules end up
     * as dragged and the hidden ones keep their order.
     */
    fun <T> move(order: List<T>, moved: T, target: T): List<T> {
        val from = order.indexOf(moved)
        val to = order.indexOf(target)
        if (from < 0 || to < 0 || from == to) return order
        val rest = order.filterNot { it == moved }
        val insertAt = if (from < to) rest.indexOf(target) + 1 else rest.indexOf(target)
        return rest.take(insertAt) + moved + rest.drop(insertAt)
    }
}
