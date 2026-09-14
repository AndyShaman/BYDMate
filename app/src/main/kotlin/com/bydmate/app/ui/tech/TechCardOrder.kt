package com.bydmate.app.ui.tech

/**
 * The cards of the «Техника» screen, in their factory order. The id is what gets persisted,
 * so it must never change once shipped — the enum name may.
 */
enum class TechCard(val id: String) {
    BATTERY_NOW("battery_now"),
    LIMITS("limits"),
    HISTORY("history"),
    MOTORS("motors"),
    CLIMATE("climate"),
    TYRES("tyres"),
}

/**
 * The order the driver dragged the cards into. Stored as a comma-separated list of ids; a card
 * missing from the stored list (added by a later version) keeps its factory place relative to
 * the cards it shipped next to, which for an appended card means the end of the grid.
 */
object TechCardOrder {

    val DEFAULT: List<TechCard> = TechCard.entries.toList()

    fun parse(raw: String): List<TechCard> {
        val byId = TechCard.entries.associateBy { it.id }
        val saved = raw.split(',')
            .mapNotNull { byId[it.trim()] }
            .distinct()
        return saved + DEFAULT.filterNot { it in saved }
    }

    fun serialize(order: List<TechCard>): String = order.joinToString(",") { it.id }

    /**
     * Drops [moved] into the slot [target] occupies, pushing the rest along — the plain
     * drag-and-drop insert, not a swap, so the cards between the two keep their relative order.
     */
    fun move(order: List<TechCard>, moved: TechCard, target: TechCard): List<TechCard> {
        val from = order.indexOf(moved)
        val to = order.indexOf(target)
        if (from < 0 || to < 0 || from == to) return order
        val rest = order.filterNot { it == moved }
        val insertAt = if (from < to) rest.indexOf(target) + 1 else rest.indexOf(target)
        return rest.take(insertAt) + moved + rest.drop(insertAt)
    }
}
