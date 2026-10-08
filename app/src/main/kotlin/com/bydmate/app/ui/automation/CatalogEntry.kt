package com.bydmate.app.ui.automation

import android.content.Context
import androidx.annotation.StringRes
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.util.appLocalizedContext

/** One entry of the car's action catalog, as a row's dropdown and the picker offer it. */
internal class CatalogEntry(val label: String, @StringRes val categoryRes: Int, val make: (Context) -> ActionDef)

/** The car's catalog sections in the picker's order. */
internal val CATALOG_SECTIONS = listOf(
    R.string.auto_cat_windows, R.string.auto_cat_climate, R.string.auto_cat_seats, R.string.auto_cat_sunroof,
    R.string.auto_cat_locks, R.string.auto_cat_body, R.string.auto_cat_light, R.string.auto_cat_mirrors,
    R.string.auto_cat_drive_mode, R.string.auto_cat_fridge,
)

/**
 * [ACTION_COMMANDS] in its order with one entry per [LevelFamily] in its category: before the
 * family's [LevelFamily.pickerBefore] entry, or after the category's last entry when it has none.
 * The editor's dropdown and the picker both build from this, so they cannot drift apart.
 */
internal fun catalogEntries(lc: Context): List<CatalogEntry> {
    val lastOfCategory = ACTION_COMMANDS.indices.associateBy { ACTION_COMMANDS[it].categoryRes }
    return ACTION_COMMANDS.flatMapIndexed { i, option ->
        val cat = option.categoryRes
        levelEntries(cat, option.toggleTarget ?: option.command, lc) +
            CatalogEntry(option.localizedName(lc), cat) { actionDefFor(option, it) } +
            if (lastOfCategory[cat] == i) levelEntries(cat, null, lc) else emptyList()
    }
}

/** [catalogEntries] grouped by [CATALOG_SECTIONS], empty sections left out. */
internal fun catalogSections(lc: Context): List<Pair<Int, List<CatalogEntry>>> {
    val entries = catalogEntries(lc)
    return CATALOG_SECTIONS.map { cat -> cat to entries.filter { it.categoryRes == cat } }.filter { it.second.isNotEmpty() }
}

private fun levelEntries(cat: Int, before: String?, lc: Context): List<CatalogEntry> =
    LevelFamily.entries.filter { it.categoryRes == cat && it.pickerBefore == before }
        .map { family -> CatalogEntry(lc.getString(family.tileRes), cat) { family.newAction(it) } }

/** What a param row's dropdown shows: the catalog name, a level command's name in the editor language, else the saved name. */
internal fun paramSelectedText(action: ActionDef, context: Context): String =
    ACTION_COMMANDS.find { it.command == action.command }?.localizedName(context)
        ?: levelActionName(action.command, context.appLocalizedContext())
        ?: action.displayName
