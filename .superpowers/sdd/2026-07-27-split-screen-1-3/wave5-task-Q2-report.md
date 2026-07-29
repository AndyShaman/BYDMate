# Wave 5 — Task Q2 Report: pane tasks ALWAYS recents-type (F-2 + F-6)

**Status:** DONE (fix-round complete)  
**Initial commit:** `538b7ba3`  
**Fix-round commit:** see Q2 fix-round section below  
**Suite after fix-round:** 2819/0/0/1 (Q2 initial 2817; fix-round +1 gentle-flip test)  
**Branch:** `worktree-split-screen`

---

## Problem (F-2 + F-6)

Root cause chain:

1. `setWindowingModeCompat` fullscreen exit path: `am stack remove $taskId` + `am start --display 0 -n "$1"` with NO `--activityType` → creates a STANDARD task (intentionally correct for fullscreen restore).
2. Next split start: `launchFreeform` calls `setWindowingModeCompat(freeform)` which tries `reflectSet` first. `reflectSet` changes `windowingMode` but **preserves** `activityType` → task becomes freeform with `activityType=STANDARD` → native caption buttons appear (F-2), and any child task spawned by this pane inherits the STANDARD type (F-6, contagion).
3. Only the shell fallback of the freeform path (`am start --activityType 3`) correctly enforces type=RECENTS; but that path is only taken when `reflectSet` throws `NoSuchMethodException`. On ROMs or firmware versions where `setTaskWindowingMode` IS present, `reflectSet` succeeds silently with the wrong type.

---

## Fix

**Invariant established:** any task placed into a freeform split pane ends up `activityType=RECENTS (3)`.

### Changed files

**`HelperDaemon.kt`**

- `internal const val ACTIVITY_TYPE_STANDARD = 1` — companion to existing `ACTIVITY_TYPE_RECENTS = 3`.

- `internal fun taskActivityType(taskId: Int): Int` — reads `WindowConfiguration.getActivityType()` for the task via the same `getTasks` reflection surface as `taskModeState` (same `ActivityThread` + `IActivityTaskManager` path). Returns -1 on any failure (task not found, reflection unavailable, cast failure).

- `setWindowingModeCompat` signature extended: `getActivityType: (Int) -> Int = { _ -> -1 }` added before `sleep`. Default is -1 (unknown) → conservative (skip `reflectSet` for freeform) → always safe because the shell path enforces `--activityType 3`. When `getActivityType` returns `ACTIVITY_TYPE_RECENTS` (task is already correct), `reflectSet` stays preferred (fast, keeps task id).

  New gate inside the function:
  ```kotlin
  val skipReflect = windowingMode == WINDOWING_MODE_FREEFORM &&
                    getActivityType(taskId) != ACTIVITY_TYPE_RECENTS
  if (!skipReflect) {
      try { reflectSet(taskId, windowingMode); return }
      catch (e: NoSuchMethodException) { /* fall through */ }
  }
  ```

  The fullscreen direction is not affected (`skipReflect` is only true for FREEFORM).

- `launchFreeform` setMode lambda: passes `getActivityType = { ti -> taskActivityType(ti) }`.
  **Initial commit left `TX_SET_TASK_WINDOWING_MODE` unwired (Q2-1); corrected in fix-round.**

**`launchAppCore`** — already correct: `--activityType $ACTIVITY_TYPE_RECENTS` is included in `modePrefix` for all freeform cold-launches. No change needed.

**Fullscreen exit path** — intentionally unchanged: `am start --display 0 -n "$1"` (no `--activityType`). Standard restore on fullscreen is the correct end-state. The invariant is enforced at pane ENTRY, not exit.

---

## Test impact on existing tests

Two existing tests in `SetWindowingModeCompatTest` tested the `reflectSet` path with the default `getActivityType` (which was previously implicit "always try reflectSet"). With the new gate, the default -1 means `skipReflect=true` for freeform → reflectSet is skipped. These tests were updated to explicitly pass `getActivityType = { _ -> ACTIVITY_TYPE_RECENTS }` so they still test the reflectSet path:

- `reflect path works - shell never touched` — now passes `ACTIVITY_TYPE_RECENTS` ✓
- `other reflect throwables are rethrown untouched` — now passes `ACTIVITY_TYPE_RECENTS` ✓

The observed behavior of these tests (same ops/exception) is unchanged; only the mechanism they test is now explicit.

---

## Anti-vacuity

Test `activityType standard + freeform skips reflectSet and relaunches with activityType 3`:

- Passes `getActivityType = { _ -> ACTIVITY_TYPE_STANDARD }`.
- Asserts `ops.none { it.startsWith("reflect:") }` — reflectSet NOT called.
- Asserts `ops.any { "--activityType 3" in it }` — shell relaunch with correct type.

Without the `skipReflect` gate, `reflectSet` WOULD be called (the default stub records `"reflect:36:5"`). The first assertion would fail. This confirms the gate is the active mechanism.

---

## New tests (4 added)

| Test | Scenario | Expected |
|------|----------|----------|
| `activityType standard + freeform skips reflectSet and relaunches with activityType 3` | type=1, mode=freeform | reflectSet NOT called; `--activityType 3` in shell cmd |
| `activityType recents + freeform uses reflectSet fast path` | type=3, mode=freeform | reflectSet called; no shell |
| `activityType unknown (-1) + freeform takes shell path conservatively` | type=-1 (default), mode=freeform | reflectSet NOT called; `--activityType 3` in shell cmd |
| `activityType check is not applied to the fullscreen direction` | type=1, mode=fullscreen, reflectSet throws NSME | no `--activityType` in any shell cmd |

---

## Build infrastructure note

KSP incremental cache was corrupted during this wave. The cache causes `NullPointerException: Storage for [.../symbolLookups/file-to-id.tab] is already registered` on `kspDebugKotlin`. Workaround used: `-Pksp.incremental=false` on all Gradle runs for this task. The actual production/test Kotlin code compiled cleanly; this is a build-tool cache issue only.

Recommend: clear `app/build/kspCaches/` before the next release build (`./gradlew clean` or manual delete).

---

## Fleet safety

Non-split launch paths (`launchApp`, `ActionDispatcher app_launch`, `TX_LAUNCH_APP`) are byte-identical in behavior — they call `launchAppCore(packageName, null, ...)` (no `windowingMode`, no `--activityType` change). The `setWindowingModeCompat` change affects two callers: `launchFreeform` (split path, intentional) and `TX_SET_TASK_WINDOWING_MODE`. The latter receives both FULLSCREEN pull-back (skipReflect=false, no behavior change) and FREEFORM gentle flip from `SplitSessionManager.forceStopIfNeeded` — the gentle flip is a split-path operation, so the Q2 invariant correctly applies. On DiLink 5 (where reflectSet throws NSME) both paths already fell back to shell; on ROMs with live API the RECENTS task now benefits from reflectSet instead of a relaunch.

---

## Deviations from brief

None from the brief (Q2-initial). Q2-1 (fix-round): `TX_SET_TASK_WINDOWING_MODE` left unwired — the comment `:190-191` falsely claimed the TX only receives FULLSCREEN, obscuring that `SplitSessionManager.forceStopIfNeeded` also sends FREEFORM through it.

Q2-4 (final fix-round, dcbb2b06): the anti-vacuity claim for the `gentle flip (freeform) with
RECENTS task uses reflectSet not shell` test was false. The test called `setWindowingModeCompat`
directly; removing the TX handler wiring left the suite green. Corrected by extracting
`handleSetWindowingModeTx` and adding two tests that directly exercise the handler body and
prove `getActivityType` is load-bearing within it.

---

## Fix round (Q2-1..Q2-3 + Q1-Minor-1 + Q1-Minor-2)

### Q2-1 (Important) — TX_SET_TASK_WINDOWING_MODE handler wired + false comment fixed

`HelperDaemon.kt:192` now passes `getActivityType = { ti -> taskActivityType(ti) }`.
`SplitSessionManager.forceStopIfNeeded` (:494) sends WINDOWING_FREEFORM through this TX (gentle flip
that preserves the app process for music). Without the wiring, `skipReflect=true` unconditionally
for any FREEFORM call — the documented "mildness" of the gentle flip was silently broken on ROMs
where reflectSet is available. The false comment (`:190-191` "Clients only send FULLSCREEN through
this TX") replaced with accurate doc of both FULLSCREEN and FREEFORM callers.

Anti-vacuity test (original claim, revised): the original commit claimed "removing the TX wiring
(default { _ -> -1 }) makes skipReflect=true and the test fails" — this claim was false. The test
called `setWindowingModeCompat` directly (with its own `getActivityType = { _ -> ACTIVITY_TYPE_RECENTS }`)
and did not exercise the TX handler wiring at all. The reviewer verified this mutation leaves the
suite green.

Fix (dcbb2b06): extracted TX handler body to `internal fun handleSetWindowingModeTx(...)` with
two new tests:
- `handleSetWindowingModeTx RECENTS task in FREEFORM direction uses reflectSet not shell`:
  passes `getActivityType = { ACTIVITY_TYPE_RECENTS }` → asserts reflectSet used, shell not.
  Anti-vacuity: call with `getActivityType = { -1 }` → shell is used → assertFalse fails.
- `handleSetWindowingModeTx unknown activityType in FREEFORM direction uses shell`:
  passes `getActivityType = { -1 }` → asserts shell used, reflectSet not.

These tests prove `getActivityType` is load-bearing within `handleSetWindowingModeTx`. They do
not catch a mutation in the TX handler's external wiring — but that gap no longer exists: the
extracted `handleSetWindowingModeTx` has no default for `getActivityType`, so omitting the argument
at the call site in `main()` is a compilation error. The wiring is enforced by the type system.

### Q2-2 (Important) — logging added to taskActivityType and setWindowingModeCompat

`taskActivityType.getOrElse`:
- Now logs `Log.w("bydmate_helper", "taskActivityType(task=$taskId) failed: ${e.message}")` on
  any reflection failure — distinguishable from the task-not-found path (which returns -1 silently
  via early `return@runCatching -1`).

`setWindowingModeCompat` freeform branch:
- Calls `getActivityType` once, stores in `type`.
- Logs `Log.d("bydmate_helper", "setWindowingModeCompat task=$taskId mode=$windowingMode → $branch")`
  where `branch` distinguishes:
  - `"reflectSet (activityType=RECENTS)"` — fast path, no relaunch
  - `"shell coerce (activityType=STANDARD)"` — type known, coercion needed
  - `"shell coerce (activityType unknown=$type, reflection broken or task not found)"` — type read failed

### Q2-3 (Minor) — KDoc warning on default getActivityType

Added NOTE to `setWindowingModeCompat` KDoc: omitting `getActivityType` silently disables the
reflectSet fast path for freeform. Every production call site must pass a real implementation.

### Q1 Minor 1 — SplitSessionManager.kt section header

`:224` "Public API — each acquires the mutex" contradicted lock-free `beginClusterSend` directly
below it. Replaced with a two-line header that accurately notes `beginClusterSend` is lock-free
and all OTHER public API acquires the mutex.

### Q1 Minor 2 — Q1 report Fix section brought to final state

Updated the "Fix" section (which described the initial commit state) to match the final post-fix-round
implementation: synchronous lock-free `beginClusterSend`, bounded noise window (`inNoiseWindow` not
`paneAlreadyDeparted`), non-suspend `onBeforeClusterSend`. Suite arithmetic corrected.
