# Wave 5 — Task Q1 Report: Departure Grace (F-1)

**Status:** DONE (fix-round complete)  
**Initial commit:** `9843c73d`  
**Fix-round commit:** `3d673f88`  
**Suite at fix-round commit (3d673f88):** 2818/0/0/1
(baseline before Q1: 2808; Q1 adds 6 tests total: 5 initial + 1 fix-round;
Q2 adds 4 more tests in commit 538b7ba3 between the two Q1 commits)  
**Branch:** `worktree-split-screen`

---

## Problem (F-1)

When BYDMate sends a split pane to the cluster via `ClusterProjectionManager.tryDirectProjection`
(direct-mode `helper.launchFreeform`), the pane task undergoes REMOVE+RELAUNCH on the cluster
display. During this transient period the watchdog tick sees:

- `taskId = -1` (task removed) → fires the **death** branch → emits spurious `PaneClosed`
- or `taskId ≥ 0, windowingMode = WINDOWING_FULLSCREEN, displayId = 0` → fires the **MAXIMIZED**
  branch → tears down the entire session, collapsing the split

Observed on-car (build 388): task IDs jumped 39→41 and 39→44; cluster `displayId` floated between
4 and 5 during the transfer.

---

## Root Cause

`handlePaneLocked` evaluates branches on every watchdog tick without context about whether BYDMate
itself initiated the pane movement. REMOVE+RELAUNCH produces observations that are
indistinguishable from genuine death or user-maximize unless the caller explicitly signals intent.

---

## Fix

**8-second departure grace window** — before `launchFreeform` fires, the caller signals the
manager which package is being transferred. The manager suppresses misclassification branches for
that pane until either: (a) a `displayId > 0` tick confirms cluster arrival (early resolve), or
(b) 8 seconds elapse (fail-open → normal classification resumes).

### Changed files

**`SplitSessionManager.kt`** (primary)

- `private const val DEPARTURE_GRACE_MS = 8_000L` — 8 s covers the worst-case REMOVE+RELAUNCH
  on DiLink 5.0 (observed < 3 s on-car).
- `private var narrowDepartureGraceDeadlineMs: Long = Long.MIN_VALUE`  
  `private var wideDepartureGraceDeadlineMs: Long = Long.MIN_VALUE`  
  Per-pane monotonic deadline; `Long.MIN_VALUE` = inactive.
- `fun beginClusterSend(pkg: String)` — synchronous lock-free public API (updated in fix-round).
  Reads `_state.value` (StateFlow, thread-safe without mutex) and writes `@Volatile Long` deadline
  fields directly — no `scope.launch`, no `mutex` acquisition. Deadline is guaranteed set before
  `launchFreeform` returns, so the watchdog sees it on the very next tick (F-Q1-3 closed).
- `handlePaneLocked` converted from expression form to block form; three guard sites added:
  - **Death branch** (`taskId == -1`): `if (graceActive) return false` — suppresses spurious
    PaneClosed.
  - **Departed branch** (`taskId != -1 && displayId > 0`): clears grace on confirmation; departed
    branch logic unchanged.
  - **MAXIMIZED branch** (`windowingMode == WINDOWING_FULLSCREEN`): `if (graceActive ||
    inNoiseWindow) return false` — `graceActive` suppresses misclassification during the 8-second
    window; `inNoiseWindow = nowMs() < postDepartureNoiseDeadline` suppresses stale display0
    readings for `POST_DEPARTURE_NOISE_MS = 3_000L` after confirmed departure (F-Q1-1 fix).
  - **Else branch** (bounds snap): `if (graceActive) return false` — skip snap mid-transfer.
- Grace reset sites: `start()` (alongside `narrowPaneDepartedEmitted` resets), `tearDownLocked`
  (alongside reroute resets), `changeApp` (per pane alongside closed/departed resets), MAXIMIZED
  path (alongside `lastStartedPair`/`lastStartMs` resets).

**`ClusterProjectionManager.kt`**

- `@Volatile var onBeforeClusterSend: ((String) -> Unit)? = null`  
  Synchronous (non-suspend, updated in fix-round) callback set by `AppModule`; called inside
  `tryDirectProjection` immediately before `helper.launchFreeform(...)`. The non-suspend type
  documents the lock-free contract: the implementation must not acquire SSM.mutex.

**`AppModule.kt`**

- `provideSplitSessionManager` now wraps the constructed manager in `.also { mgr -> ... }` to
  wire `ClusterProjectionManager.onBeforeClusterSend = { pkg -> mgr.beginClusterSend(pkg) }`.
  Same pattern as the existing `splitPreferences` and `applyCalibratedBounds` wiring.

---

## `resolveClusterDisplay` note

`ClusterProjectionManager.resolveClusterDisplay()` is called fresh on every invocation — it
queries `DisplayManager.displays` each time and is NOT cached. The `onBeforeClusterSend` callback
path does not depend on display resolution and is not affected by this.

---

## Anti-vacuity mutation evidence

Test `Q1 anti-vacuity - without grace fullscreen@display0 ends the session`:

- Sets up a normal active session, does NOT call `beginClusterSend`.
- Injects `taskId = 11, windowingMode = 1, displayId = 0` on the narrow pane.
- Asserts `SessionEnded(EndReason.MAXIMIZED)` is emitted.

This is the **negative proof**: the same observable that Q1's positive tests suppress causes
session teardown when grace is absent. Together the pair confirms the grace mechanism is what
prevents the false positive, not a structural difference in the test setup.

---

## Test coverage (5 new tests)

| Test | Scenario | Expected |
|------|----------|----------|
| `Q1 grace active + fullscreen@display0 does not trigger MAXIMIZED` | MAXIMIZED-branch observation during grace | session stays Active, `setTaskWindowingMode` NOT called |
| `Q1 grace active + task death does not emit PaneClosed` | taskId=-1 during grace | no PaneClosed, session Active |
| `Q1 grace active + departed resolves grace - post-departure noise does not end session` | displayId=4 tick resolves grace; follow-up displayId=0 noise | first: PaneClosed; second: no SessionEnded |
| `Q1 grace expired + fullscreen@display0 triggers MAXIMIZED normally` | fake clock: grace set at t=0, ticked at t=8001 | SessionEnded(MAXIMIZED) fires |
| `Q1 anti-vacuity - without grace fullscreen@display0 ends the session` | no beginClusterSend call | SessionEnded(MAXIMIZED) fires |

**Implementation fix during test authoring:** test 4 (`grace expired`) originally set `fakeNow`
BEFORE `runCurrent()`, causing `beginClusterSend`'s coroutine to capture `nowMs() = 8001` and set
`deadline = 16001` instead of `8000`. This made the watchdog tick see grace still active →
`awaitItem()` hung → JVM OOM after ~5 min. Fix: `runCurrent()` called first (coroutine captures
`nowMs() = 0`), then `fakeNow = 8_001L`.

---

## Deviations from brief (updated after fix-round)

1. **`|| paneAlreadyDeparted` in MAXIMIZED branch** (F-Q1-1): the initial implementation added an unbounded `paneAlreadyDeparted` guard to suppress post-departure noise. This was not in the brief and created an absorbing state (session non-terminable after native departure). **Accepted deviation, corrected in fix-round** — replaced with bounded 3-second `POST_DEPARTURE_NOISE_MS` window.

2. **Anti-vacuity as control test, not mutation**: the brief asked for mutation evidence ("revert the grace suppression → test fails"). The initial implementation provided a control test (`Q1 anti-vacuity - without grace fullscreen@display0 ends the session`) that tests the baseline without grace, not a mutation of the with-grace test. The reviewer confirmed the mechanism is non-vacuous (conducted mutations manually). Control test retained but not called a mutation.

---

## Fix round (F-Q1-1..F-Q1-6)

**Commit:** `3d673f88`

### F-Q1-1: Absorbing state → bounded post-departure noise window

Replaced `|| paneAlreadyDeparted` in the MAXIMIZED branch with a 3-second bounded window (`POST_DEPARTURE_NOISE_MS = 3_000L`). The departed branch now sets `narrowPostDepartureNoiseDeadlineMs = nowMs() + POST_DEPARTURE_NOISE_MS` when departure is first confirmed. Within the window, `nowMs() < postDepartureNoiseDeadline` suppresses fullscreen@display0 as stale-cache noise. After the window, fullscreen@display0 means the pane returned from cluster → normal MAXIMIZED teardown. New `@Volatile` fields cleared in `start()`, `tearDownLocked()`, `changeApp()`, MAXIMIZED path.

New test: `Q1 post-departure noise window expired - fullscreen@display0 triggers MAXIMIZED` — verifies that at `fakeNow = 3_001L` (past deadline=3000) the session ends with `SessionEnded(MAXIMIZED)` and `setTaskWindowingMode(10, 1)` is called on the other pane.

### F-Q1-2 + F-Q1-3: Lock-free `beginClusterSend`, @Volatile deadlines

`beginClusterSend` rewritten as synchronous lock-free function. Removes `scope.launch` and `mutex.withLock`. Reads `_state.value` (StateFlow — thread-safe without lock) and writes `@Volatile Long` fields directly. This:
- Eliminates the lock-order cycle: CPM.mutex → (was: SSM.mutex via scope.launch enqueue); SSM.mutex → CPM.mutex (via `applyCalibratedBoundsToTask`) — only `SSM → CPM` is the permitted direction.
- Guarantees the deadline is set before `launchFreeform` starts REMOVE+RELAUNCH (F-Q1-3 race closed).

`onBeforeClusterSend` callback type changed from `suspend (String) -> Unit` to `(String) -> Unit` (synchronous, documents lock-free contract).

Both false KDocs corrected:
- `ClusterProjectionManager.onBeforeClusterSend` (was: "no lock-order cycle, SSM never calls back into CPM" — false; `applyCalibratedBoundsToTask` does exactly that). Now documents the real cycle and why it is safe.
- `ClusterProjectionManager.applyCalibratedBoundsToTask` (was: "CPM never calls back into SSM" — false since Q1). Now documents SSM→CPM as the only permitted order.

### F-Q1-4: Honest Deviations section

Added above.

### F-Q1-5: Restored corrupted comment

`SplitSessionManagerTest.kt:1250`: `// 1 flip must not have been called for the narrow task.` → `// WINDOWING_FULLSCREEN flip must not have been called for the narrow task.` (trace of global `replace_all` from Task P that clobbered an unrelated comment).

### F-Q1-6: Tests 1-3 converted to injectable clock

Tests 1, 2, 3 now pass `nowMs = { fakeNow }` and use `fakeNow = 0L` (grace active: 0 < 8000). Tests are no longer implicitly dependent on real elapsed milliseconds being < 8 s.

---

## Fleet safety

Changes are additive and gated to the `SplitSessionManager` path. No behaviour changes outside
the split session path. The `else` clause `if (graceActive) return false` skips bounds snap only
during the 8-second window; bounds are snapped normally on every subsequent tick.
