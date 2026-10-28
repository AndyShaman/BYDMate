package com.bydmate.app.ui.tech

import com.bydmate.app.data.nativestack.VehicleKind

/**
 * The cards of the «Техника» screen, in their factory order. The id is what gets persisted,
 * so it must never change once shipped — the enum name may.
 */
enum class TechCard(val id: String) {
    BATTERY_NOW("battery_now"),
    LIMITS("limits"),
    HISTORY("history"),
    MOTORS("motors"),
    /** «Состояние машины»: climate on top, tyres below. Keeps the shipped climate id. */
    CAR_STATE("climate"),
    /** DM-i hybrid only (#184): in the order only on a hybrid, see [TechCardOrder.forKind]. */
    FUEL("fuel"),
    DIAGNOSTICS("diagnostics"),
}

/**
 * The order the driver dragged the cards into. Stored as a comma-separated list of ids; a card
 * missing from the stored list (added by a later version) keeps its factory place relative to
 * the cards it shipped next to, which for an appended card means the end of the grid.
 */
object TechCardOrder {

    val DEFAULT: List<TechCard> = TechCard.entries.filterNot { it == TechCard.FUEL }

    /**
     * Ids of cards merged away. «Шины» joined «Климат» in «Состояние машины», so a stored `tyres`
     * becomes that card and the merged card takes the earlier of the two old slots.
     */
    private val LEGACY_IDS = mapOf("tyres" to TechCard.CAR_STATE)

    fun parse(raw: String): List<TechCard> {
        val byId = TechCard.entries.associateBy { it.id } + LEGACY_IDS
        val saved = raw.split(',')
            .mapNotNull { byId[it.trim()] }
            .distinct()
        return saved + DEFAULT.filterNot { it in saved }
    }

    /**
     * The order for this car: a hybrid gets the fuel card (appended when the stored order predates
     * it), any other car drops it, so an EV's stored order never gains `fuel`.
     */
    fun forKind(order: List<TechCard>, kind: VehicleKind): List<TechCard> = when {
        kind != VehicleKind.HYBRID -> order.filterNot { it == TechCard.FUEL }
        TechCard.FUEL in order -> order
        else -> order + TechCard.FUEL
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
