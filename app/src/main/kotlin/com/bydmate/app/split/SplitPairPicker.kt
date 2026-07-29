package com.bydmate.app.split

/** Which role (pane) the user is configuring in the settings pair picker. */
enum class SplitRole { WIDE, NARROW }

/**
 * Pure helper: computes the [SplitPair] to persist after the user picks [pkg] for [role].
 *
 * Rules:
 * - [stored] is not null (a pair already exists): update only the chosen role's package,
 *   keep the other member and [SplitPair.narrowSide] unchanged.
 * - [stored] is null but [pendingOther] is not null (user has already picked the other side
 *   in-session but nothing is stored yet): build a brand-new pair with [SplitSide.RIGHT] as
 *   the default narrow side, matching the overlay first-pair flow default.
 * - [stored] is null and [pendingOther] is null: can't persist yet, returns null — the caller
 *   keeps the pick in local UI state until the other side is also chosen.
 *
 * Degenerate guard: if the candidate pair has the same package in both slots, returns the
 * unchanged [stored] (no-op). This prevents persisting an invalid pair even when the UI
 * exclusion filter is stale (e.g. the overlay picker changed the stored pair while settings
 * was open).
 */
fun applyPick(
    stored: SplitPair?,
    pendingOther: String?,
    role: SplitRole,
    pkg: String,
): SplitPair? {
    val candidate: SplitPair? = when {
        stored != null -> when (role) {
            SplitRole.WIDE   -> stored.copy(widePkg = pkg)
            SplitRole.NARROW -> stored.copy(narrowPkg = pkg)
        }
        pendingOther != null -> when (role) {
            SplitRole.WIDE   -> SplitPair(narrowPkg = pendingOther, widePkg = pkg, narrowSide = SplitSide.RIGHT)
            SplitRole.NARROW -> SplitPair(narrowPkg = pkg, widePkg = pendingOther, narrowSide = SplitSide.RIGHT)
        }
        else -> null
    }
    return if (candidate != null && candidate.narrowPkg == candidate.widePkg) stored else candidate
}
