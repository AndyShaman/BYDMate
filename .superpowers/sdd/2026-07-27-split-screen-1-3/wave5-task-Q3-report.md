# Wave 5 — Task Q3 Report: COVERED teardown (F-3)

**Status:** DONE  
**Commit:** see below  
**Suite:** 2826/0/0/1 (was 2819/0/0/1; +7 Q3 tests)  
**Branch:** `worktree-split-screen`

---

## Problem (F-3)

A foreign fullscreen app appearing over an Active split session (navigator returning from cluster
fullscreen, user tapping a recents entry, Home + launching another app) left the split overlays
**orphaned**: the pill button kept floating over the foreign app, and ex-pane tasks reopened from
recents came up with pane geometry or crooked windows (F-5/F-11).

---

## Root Cause

`SplitSessionManager.tickLocked` only classified per-pane states (death/departed/MAXIMIZED/bounds).
When a foreign fullscreen app covered the split on display 0, neither pane's state changed — the
watchdog saw two healthy freeform tasks and kept the session Active indefinitely. The overlays had
no trigger to tear down.

---

## Fix

**COVERED teardown**: when a foreign fullscreen app is on top of display 0 for 2 consecutive
watchdog ticks, end the session WITHOUT touching the pane tasks (music keeps playing, pane tasks
remain freeform in the background; recents-typed tasks are invisible in the recents list anyway).

### Protocol: TX_GET_TOP_TASK (new TX 33)

`HelperBinderProtocol.TX_GET_TOP_TASK = FIRST_CALL_TRANSACTION + 32` (33).

Request: (no args).  
Reply: `int status (0=ok/-1=error), String pkg, int taskId, int windowingMode, int activityType, int displayId`.  
Old daemon → transact returns false → client returns null → COVERED detection auto-disarms (fail-safe).

Same reflection surface as `TX_GET_TASK_STATE` and `TX_GET_TOP_PACKAGE` (getTasks reflection).

### Changed files

**`HelperBinderProtocol.kt`**
- Added `TX_GET_TOP_TASK` constant + KDoc.

**`HelperDaemon.kt`**
- Added `private data class TopTaskInfo(pkg, taskId, windowingMode, activityType, displayId)`.
- Added `private fun topTaskInfo(): TopTaskInfo?` — reads top task via `getTasks(1, false, false)`,
  reads pkg from topActivity/baseActivity ComponentName, taskId from taskId/id field, windowingMode
  and activityType from WindowConfiguration, displayId from TaskInfo.displayId field. Same
  pattern as `topTaskPackage()` + `findTaskState()`. Returns null on any failure.
- Added `TX_GET_TOP_TASK` handler: writes status=0 + 5 fields on success, status=-1 on null.

**`HelperClient.kt`**
- Added `data class TopTaskInfo(pkg, taskId, windowingMode, activityType, displayId)` — mirrors
  daemon's private class; published for callers (SplitSessionManager).
- Added `suspend fun getTopTask(): TopTaskInfo?` interface method + KDoc.
- Added `HelperClientImpl.getTopTask()` implementation via `transactParsed(TX_GET_TOP_TASK)`.
  Reads status, pkg, then 4 ints (taskId/windowingMode/activityType/displayId).

**`SplitSessionManager.kt`**
- Added `COVERED` to `EndReason` enum.
- Added `private const val OUR_PACKAGE = "com.bydmate.app"` — excluded from COVERED detection.
- Added `private var coveredTickCount = 0` field (all accesses inside `mutex`).
- Added `import com.bydmate.app.data.vehicle.TopTaskInfo`.
- `start()`: added `coveredTickCount = 0`.
- `tearDownLocked()`: added `coveredTickCount = 0`.
- MAXIMIZED inline teardown: added `coveredTickCount = 0`.
- `tickLocked()`: COVERED detection block after transient-helper-failure check.
  Condition `panesBothAliveFreeformOnMain`: both panes alive (`taskId != -1`), both freeform
  (`windowingMode == WINDOWING_FREEFORM`), both on main display (`displayId == 0`), neither
  pane in departure grace. When true, calls `helper.getTopTask()`:
  - null → `coveredTickCount = 0` (helper hiccup, skip this tick)
  - foreign fullscreen (displayId=0, mode=FULLSCREEN, pkg not in panes, not OUR_PACKAGE) →
    increment `coveredTickCount`; at ≥2 ticks, execute COVERED teardown
  - otherwise → `coveredTickCount = 0`
  
  COVERED teardown (inline, NOT via `tearDownLocked` — would cancel executing watchdog job):
  - Cancel `mediaPollJob`
  - Clear `rerouteCount`, `rerouteStoodDown`, `lastStartedPair`, `lastStartMs`
  - Clear all `@Volatile` departure-grace and noise deadline fields
  - `coveredTickCount = 0`
  - `backdrop.hide()`
  - `_state.value = SplitSessionState.Idle`
  - `_events.emit(SplitEvent.SessionEnded(EndReason.COVERED))`
  - `return false` — watchdog loop exits naturally

**`SplitSessionManagerTest.kt`**
- Added `import org.junit.Assert.assertFalse`, `import org.junit.Assert.assertTrue`.
- Added `import com.bydmate.app.data.vehicle.TopTaskInfo`.
- Added `private fun foreignFullscreenTopTask(pkg)` helper.
- Added 7 Q3 tests (see table below).

**`SplitOverlayController.kt`**: NOT changed — verified that the `is SplitEvent.SessionEnded`
branch at :204 handles ALL reasons without filtering. `SessionEnded(COVERED)` is handled
identically to EXIT/MAXIMIZED: `logic.dismissPicker() + tearDownPill() + clearAppCache()`. ✓

---

## Anti-vacuity (corrected post-review)

| Mutation | Effect | Verified |
|---|---|---|
| Change `coveredTickCount >= 2` to `>= 1` | `Q3 flicker immunity` and `Q3 anti-vacuity debounce` tests fail | yes (review confirmed: мутация B роняет) |
| Remove `topTask.pkg != current.pair.narrowPkg && topTask.pkg != current.pair.widePkg` | `Q3 top task is session pane pkg` fails | **original test was tautological** — fixed in dcbb2b06; mutation NOT verified in the Q3 commit |
| Remove `topTask.pkg != OUR_PACKAGE` | `Q3 top task is our own package` fails | yes (review confirmed mutation C роняет) |
| Remove `!narrowGrace && !wideGrace` from `panesBothAliveFreeformOnMain` | `Q3 grace interaction` fails | **original test used taskId=-1 which bypassed grace** — fixed in dcbb2b06; mutation NOT verified in the Q3 commit |

**Note:** The Q3 commit report claimed "Remove `topTask.pkg != current.pair.narrowPkg` … test fails"
and "Remove grace conditions … test fails" — both claims were false. Mutations A and D both left
the suite green. The reviewer verified this with actual mutation runs. The tests were rewritten in
the fix-round commit `dcbb2b06` so that the mutations now actually fail:
- Q3-2: `Q3 top task is session pane pkg` now uses `mgr.events.test{}` and checks that no
  `SessionEnded(COVERED)` event was emitted — correctly fails when the pane-pkg exclusion is removed.
- Q3-3: `Q3 grace is the sole COVERED suppressor when both panes alive-freeform` (new test) keeps
  both panes alive-freeform; the only suppressor of COVERED during the grace window is the grace
  condition itself — correctly fails when the grace conditions are removed.

---

## Test coverage (9 tests in final state after fix-round)

| Test | Scenario | Expected |
|------|----------|----------|
| `Q3 foreign fullscreen for 2 ticks emits COVERED and hides backdrop` | Foreign fullscreen top task for 2 ticks | `SessionEnded(COVERED)`, backdrop hidden, NO `setTaskWindowingMode` on panes |
| `Q3 flicker immunity - foreign fullscreen for 1 tick then gone` | 1 tick covered, 2nd tick session pane back | session stays Active |
| `Q3 top task is session pane pkg - NOT COVERED` | topTask.pkg = narrowPkg, fullscreen, both panes alive-freeform | NO `SessionEnded(COVERED)` in event stream |
| `Q3 top task is our own package - NOT COVERED` | topTask.pkg = "com.bydmate.app" | session stays Active |
| `Q3 getTopTask returns null - no classification change` | `getTopTask()` returns null | no state change, session stays Active |
| `Q3 anti-vacuity - removing 2-tick debounce would fire COVERED on 1st tick` | 1 tick foreign + 1 tick pane back | session stays Active (debounce is what prevents it) |
| `Q3 Q1-grace interaction - departure grace pkg appears fullscreen` | narrow pane dead (taskId=-1) during departure grace | NOT COVERED (panesBothAliveFreeformOnMain=false via dead pane) |
| `Q3 grace is the sole COVERED suppressor when both panes alive-freeform` (new, dcbb2b06) | Both alive-freeform, grace armed, foreign fullscreen | COVERED suppressed during grace, fires post-grace |

---

## Deviations from brief (corrected)

- Brief: "Q1 grace interaction: assert this in a test" — original test used a dead pane (taskId=-1)
  which bypassed grace conditions. Grace conditions were untested. Fixed in `dcbb2b06` with new test
  `Q3 grace is the sole COVERED suppressor` where both panes are alive-freeform and grace is the
  only suppressor.
- Original pane-pkg exclusion test was tautological (assertion always true). Fixed in `dcbb2b06`
  with Turbine event collection.
- Anti-vacuity table in the original Q3 commit report contained two false claims. Corrected above.
- All other brief items implemented as prescribed. ✓

---

## Fleet safety

Changes are additive and gated to the Active split session path. Non-split launch paths are
byte-identical. Old daemons (pre-Q3) return false on TX_GET_TOP_TASK → client returns null →
`coveredTickCount = 0` (helper hiccup path) → no COVERED fires → auto-disarms against old daemons.

`SplitOverlayController.SessionEnded` branch has no reason-based filtering — COVERED is handled
identically to EXIT/MAXIMIZED (pill teardown + picker dismiss). No change to SplitOverlayController.
