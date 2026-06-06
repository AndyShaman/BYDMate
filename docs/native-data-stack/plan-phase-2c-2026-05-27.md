# Phase 2c Research Plan — autoservice Write-Channel Permission Map

> **For operator + orchestrator:** This is a *research* plan, not a code plan. Most tasks are operator-in-car live probes on Andy's real Leopard 3 (DiLink ADB `192.168.2.68:5555`). Subagent execution is **not** appropriate for live snaps — orchestrator coordinates the operator (Andy) directly. The few code/script tasks (Tasks 1, 5, 8) may be subagent'd with `model: "sonnet"`.

**Goal:** Produce a hard fact table — for every safe-tier candidate write fid, can BYDMate (running as shell uid via `app_process`) call `autoservice.transact(6)` without `SecurityException` *and* see the state change reflected in the next `transact(5)` read? Output is the input for Phase 2 spec.

**Why this blocks Phase 2:** `reference_autoservice_write_channel.md` already showed `tx=6` from the BYDMate app process returns `SecurityException` on Leopard 3 (caller uid mismatch). `feedback_autoservice_permission_model.md` says `libbydautoservice.so` enforces a per-fid whitelist + signature permission fallback. Phase 2 (real write channel) needs to know which fids fall on which side of that fence — otherwise we either over-engineer an `aps_diplus`-style daemon for everything, or under-engineer and ship buttons that silently fail.

**Predecessor:** Phase 2b validated single AC-temp no-op write under shell-uid (`scripts/native-stack/write-probe/2b-run.sh` PASS, 2026-05-27). Probe infra (`WriteProbe.java`, `probe.dex`, push pattern) reused as-is.

**Successor:** Phase 2 spec + plan (`docs/native-data-stack/spec-phase-2-2026-XX-XX.md`) authored from Phase 2c findings.

**Branch:** `feature/native-data-stack` (continues from Phase 1.5 work).

---

## Safety hard rules

These apply to every live probe in this plan. Violation = stop session, audit, before continuing.

- **READ-ONLY `tx=5`/`tx=7`/`tx=9` everywhere by default.** `tx=6` (setInt) only on fids that are in `round1-permission-probes.csv` *and* in the safe tier.
- **Safe tier:** climate (HVAC), windows (front/rear/sunroof), locks, interior/ambient lights, mirror heat, seat heat/vent levels. Anything that a passenger could toggle from a physical button.
- **Banned tier — never written under any probe, even as no-op:** anything on `dev=1014` (Statistic — battery aggregates), `dev=1009` (charging gun state), `dev=1011` (gear), `dev=1012` (engine power), `dev=1013` (speed), `dev=1023` (vehicle/powerState), `dev=1016` (tire), `dev=1006` (drive mode / work mode), `dev=1007` (seatbelt sensor), `dev=1032` (door lock state-of-truth), `dev=1004` light enums that map to head-unit only (not high-beam).
- **No-op writes only in Round 1.** The probe reads the current value via `tx=5`, then writes the *same* value back via `tx=6`. Any value change is an unintended side-effect.
- **Car state:** Round 1 = parked, gear P, IGN ON, AC off, doors closed, no key motion, no charging. Round 2 = same, but operator confirms each visible toggle physically.
- **Stop on first surprise.** If a probe returns a status code other than `0` / `-10011` / `-10013`, halt the batch, save the partial results, file the surprise to memory before next pass.
- **DO NOT touch BMS / MCU / CAN / firmware writes.** `feedback_system_sdk_safety.md`.
- **No release builds during Phase 2c.** Probes live on `feature/native-data-stack` and stay as scripts under `scripts/native-stack/write-probe/`. No APK ships from this phase.

---

## File map

### Already exist (untracked — commit in Task 1)
- `scripts/native-stack/write-probe/WriteProbe.java` — single-fid probe (tx=5/6/7/10).
- `scripts/native-stack/write-probe/probe.dex` — compiled.
- `scripts/native-stack/write-probe/build.sh` — rebuild dex.
- `scripts/native-stack/write-probe/2b-run.sh` — Phase 2b validation (AC temp no-op write).
- `scripts/native-stack/write-probe/phase2c/round1-permission-probes.csv` — 39 candidate fids in safe tier (climate / windows / locks / lights — full coverage).
- `scripts/native-stack/write-probe/phase2c/round1-permission-probes.sh` — batch runner.

### Create
- `scripts/native-stack/write-probe/phase2c/round2-visible-toggle.csv` — subset of Round 1 `ACCESSIBLE` rows where a physical change is observable. Generated in Task 5.
- `scripts/native-stack/write-probe/phase2c/round2-visible-toggle.sh` — runner: pick the *opposite* of current value (or next step), write, read-back, wait operator confirmation, write back to original.
- `docs/native-data-stack/permission-map-phase-2c-2026-05-27.md` — final permission map (the deliverable for Phase 2 spec input).

---

## Group A — Pre-flight (Mac only)

### Task 1: Commit existing Phase 2b/2c artifacts

**Files:** all files in `scripts/native-stack/write-probe/` (currently untracked).

- [ ] **Step 1 — Inspect untracked files**

  ```bash
  git status -- scripts/native-stack/write-probe/
  ```
  Expected: `WriteProbe.java`, `build.sh`, `2b-run.sh`, `probe.dex`, `phase2c/round1-permission-probes.csv`, `phase2c/round1-permission-probes.sh`.

- [ ] **Step 2 — Verify `probe.dex` is a build artifact, not committed**

  ```bash
  file scripts/native-stack/write-probe/probe.dex
  ```
  Expected: `Dalvik dex file version 035`. If yes, leave it tracked anyway — it's small (~4 KB) and saves the operator a rebuild on a fresh checkout.

- [ ] **Step 3 — Commit**

  ```bash
  git add scripts/native-stack/write-probe/
  git commit -m "phase-2c(probe): commit WriteProbe + probe.dex + round1 batch runner"
  ```

---

### Task 2: Dry-run `round1-permission-probes.sh` against simulated input (Mac only)

We can't execute the script without the car, but we can verify the script parses the CSV and produces the expected header without crashing.

- [ ] **Step 1 — Dry-run with `ADB=true` substitution**

  ```bash
  cd scripts/native-stack/write-probe/phase2c
  ADB=/usr/bin/true ./round1-permission-probes.sh
  ```
  Expected: script runs, header printed, every row prints `UNKNOWN(rs=?,ws=?)` (because `true` returns nothing parseable). No bash errors. Result CSV created.

- [ ] **Step 2 — Confirm 39 rows processed**

  ```bash
  tail -n +2 round1-results-*.csv | wc -l
  ```
  Expected: `39`. Delete the dry-run output:

  ```bash
  rm round1-results-*.csv
  ```

- [ ] **Step 3 — Commit nothing**

  This task is a smoke check only. Move on.

---

### Task 3: Sanity-check ADB connectivity to DiLink

Operator step, but executed from Andy's Mac, no driver seat needed yet (parking lot / driveway is fine, car parked, IGN OFF is OK).

- [ ] **Step 1 — Ping the head unit**

  ```bash
  adb connect 192.168.2.68:5555
  adb -s 192.168.2.68:5555 shell id
  ```
  Expected: `uid=2000(shell) gid=2000(shell) ...`. If not, `reference_dilink_adb.md` covers reconnect.

- [ ] **Step 2 — Verify `autoservice` is reachable**

  ```bash
  adb -s 192.168.2.68:5555 shell service list | grep autoservice
  ```
  Expected: `autoservice: [android.gui.BYDAutoServer]`.

- [ ] **Step 3 — Smoke `probe.dex`**

  ```bash
  adb -s 192.168.2.68:5555 push scripts/native-stack/write-probe/probe.dex /data/local/tmp/probe.dex
  adb -s 192.168.2.68:5555 shell "CLASSPATH=/data/local/tmp/probe.dex \
      app_process /system/bin --nice-name=bydmate_probe \
      com.bydmate.probe.WriteProbe 5 1014 1246777400"
  ```
  Expected: SOC read returns something like `status=0 value=...`. If `ERR autoservice not found` — DiLink is in a bad state, abort.

---

## Group B — Round 1 live probe (operator-in-car, ~30 min)

### Task 4: Round 1 batch run — permission classification

**Pre-conditions (operator):**
- Car parked, gear P, parking brake on.
- IGN ON (button press, no driving). AC OFF.
- All doors closed, all windows closed, sunroof closed, locks engaged.
- Phone/iPad with adb tethered to DiLink WiFi.

- [ ] **Step 1 — Run Round 1**

  ```bash
  cd scripts/native-stack/write-probe/phase2c
  ./round1-permission-probes.sh | tee round1-console-$(date +%Y%m%d-%H%M%S).log
  ```

  Expected runtime: ~2 minutes (39 fids × ~3 s/fid).

  Expected output:
  - Header table on stdout.
  - One line per fid with `verdict` ∈ {`ACCESSIBLE`, `READ_ONLY`, `READ_SENTINEL`, `DENIED`, `UNKNOWN(...)`}.
  - Final summary count by verdict.
  - Results saved to `round1-results-YYYYMMDD-HHMMSS.csv`.

- [ ] **Step 2 — Post-run physical sanity check (operator)**

  - Did any window move? Sunroof shift? Light change? AC fan kick in? *Expected:* no.
  - If any unintended change happened, capture: which fid was active at the time (last line printed), and revert manually via the head unit's physical button. File a `feedback_*.md` memory before continuing.

- [ ] **Step 3 — Commit results**

  ```bash
  git add scripts/native-stack/write-probe/phase2c/round1-results-*.csv \
          scripts/native-stack/write-probe/phase2c/round1-console-*.log
  git commit -m "phase-2c(probe): Round 1 batch permission probe — Leopard 3 results"
  ```

---

### Task 5: Analyse Round 1 — classify findings

Mac-only task. Subagent-eligible (read CSV, produce markdown summary).

**Files:**
- Create: `docs/native-data-stack/permission-map-phase-2c-2026-05-27.md`

- [ ] **Step 1 — Aggregate by category × verdict**

  ```bash
  cd scripts/native-stack/write-probe/phase2c
  csv=$(ls -t round1-results-*.csv | head -1)
  awk -F, 'NR>1 {print $1","$NF}' "$csv" | sort | uniq -c | sort -rn
  ```

  Expected output style:
  ```
       5 windows,ACCESSIBLE
       4 climate,DENIED
       ...
  ```

- [ ] **Step 2 — Compare with `feedback_autoservice_validated.md` and `feedback_autoservice_permission_model.md`**

  For each row's verdict, cross-reference whether the memory note already predicted the outcome. Surprises = note in the markdown.

- [ ] **Step 3 — Write the permission map**

  Skeleton for the doc:

  ````markdown
  # autoservice Write-Channel Permission Map — Leopard 3 (Phase 2c Round 1)

  **Date:** 2026-05-27
  **Build:** native shell-uid app_process via WriteProbe.java
  **Probe protocol:** tx=5 read → tx=6 setInt same value → tx=5 readback. No-op writes only.

  ## Summary

  | Category | ACCESSIBLE | READ_ONLY | DENIED | READ_SENTINEL |
  |----------|-----------:|----------:|-------:|--------------:|
  | climate  |  N | N | N | N |
  | windows  |  N | N | N | N |
  | locks    |  N | N | N | N |
  | lights   |  N | N | N | N |
  | seats    |  N | N | N | N |

  ## Per-fid table

  | category | name | dev | fid | verdict | notes |
  |----------|------|----:|----:|---------|-------|
  | ... | ... | ... | ... | ... | ... |

  ## Surprises

  - (anything that contradicts `feedback_autoservice_validated.md` or `reference_autoservice_write_channel.md`)

  ## Implications for Phase 2 design

  - Fids that probe ACCESSIBLE → can be driven directly from BYDMate via the existing `AdbOnDeviceClient` write barrier (regex already allows `service call autoservice [579] i32 \d+ i32 -?\d+` — extend to allow tx=6 explicitly).
  - Fids that probe DENIED → require the `aps_diplus`-style persistent shell-uid daemon (one-time `app_process --nice-name=aps_diplus` launch, holds Binder reference, BYDMate communicates over local socket / Intent broadcast). Quantify the gap; if N is small, the daemon may be worth it; if N is large or the gap is "all comfort controls," the daemon is mandatory.
  ````

  Fill in actual numbers from Step 1, actual rows from the CSV.

- [ ] **Step 4 — Commit**

  ```bash
  git add docs/native-data-stack/permission-map-phase-2c-2026-05-27.md
  git commit -m "phase-2c(docs): permission map Round 1 — Leopard 3 fid classification"
  ```

---

## Group C — Round 2 visible toggle (operator-in-car, ~45 min)

Round 2 only runs if Round 1 found at least one `ACCESSIBLE` row. If everything is `DENIED`, skip to Group D (the answer is "must use daemon").

### Task 6: Generate Round 2 target list

Mac-only.

**Files:**
- Create: `scripts/native-stack/write-probe/phase2c/round2-visible-toggle.csv`

- [ ] **Step 1 — Extract Round 1 ACCESSIBLE rows**

  ```bash
  cd scripts/native-stack/write-probe/phase2c
  csv=$(ls -t round1-results-*.csv | head -1)
  echo "category,name,dev,fid,read_value,opposite_value,observable_change" > round2-visible-toggle.csv
  awk -F, 'NR>1 && $NF=="ACCESSIBLE" {print $1","$2","$3","$4","$6","$6","}' "$csv" \
      >> round2-visible-toggle.csv
  ```

  Manually edit each row: in the `opposite_value` column, replace `$6` with the value that makes the toggle physically visible (e.g., if `windowFL=0` closed, opposite=`50` half-open; if `acStatus=0` off, opposite=`1` on). Annotate `observable_change` with what to look for ("driver window drops half-way", "interior light comes on").

  > Rationale: probing with the same value tells us only about permissions; a real toggle tells us whether the write actually reaches the underlying hardware and not a sandboxed shim.

- [ ] **Step 2 — Safety review of the target list**

  Operator + orchestrator review each row before running. If anything is on the banned tier, drop it. If a "visible toggle" requires moving a window > 25% with people near the car, defer it to a static test.

- [ ] **Step 3 — Commit target list**

  ```bash
  git add scripts/native-stack/write-probe/phase2c/round2-visible-toggle.csv
  git commit -m "phase-2c(probe): Round 2 visible-toggle target list"
  ```

---

### Task 7: Write Round 2 runner script

**Files:**
- Create: `scripts/native-stack/write-probe/phase2c/round2-visible-toggle.sh`

- [ ] **Step 1 — Script behaviour**

  For each row in `round2-visible-toggle.csv`:
  1. `tx=5` read current value → record `before`.
  2. `tx=6` write `opposite_value`.
  3. Wait 1500 ms (HVAC/window response time).
  4. `tx=5` readback → record `after`.
  5. Pause script (e.g. `read -p "Press ENTER to revert"`) so the operator can confirm the physical change.
  6. `tx=6` write `before` to revert.
  7. `tx=5` readback → record `final`.
  8. Append to result CSV: `category,name,dev,fid,before,target,after,final,operator_observed,verdict`.

  `verdict`:
  - `WRITE_TAKES_EFFECT` — `after == target` and operator confirmed observation.
  - `WRITE_SILENT_FAIL` — `after == before` (no exception, no effect).
  - `WRITE_EXC` — exception on step 2.
  - `READBACK_BROKEN` — `after` matches target but operator did not observe the change (data path works, hardware path doesn't).

- [ ] **Step 2 — Implement the script** (mirror style of `round1-permission-probes.sh`, add the manual pause and operator-observed column).

- [ ] **Step 3 — Dry-run with `ADB=/usr/bin/true`**

  Same pattern as Task 2: prove the script reads the CSV and produces output without crashing.

- [ ] **Step 4 — Commit**

  ```bash
  git add scripts/native-stack/write-probe/phase2c/round2-visible-toggle.sh
  git commit -m "phase-2c(probe): Round 2 visible toggle runner with operator gating"
  ```

---

### Task 8: Round 2 live run (operator-in-car)

**Pre-conditions:**
- Same as Task 4 (parked, IGN ON, AC OFF default, doors/windows closed).
- Operator clear of windows/doors during writes that may move them.
- No bystanders close to the car.

- [ ] **Step 1 — Run**

  ```bash
  cd scripts/native-stack/write-probe/phase2c
  ./round2-visible-toggle.sh | tee round2-console-$(date +%Y%m%d-%H%M%S).log
  ```

  At each `Press ENTER to revert` prompt, operator looks at the car (window, light, climate fan, lock), notes Y/N in the script's stdin prompt (or in a side notebook). Then ENTER continues, script reverts.

- [ ] **Step 2 — Per-row sanity**

  - If any row's `verdict` is `WRITE_EXC` mid-batch, that fid is *more* restricted than Round 1 said (Round 1 might have written a passthrough value, Round 2 catches the real check). File the surprise.
  - If any row results in physical hardware *not* reverting (e.g., window stuck mid-way), operator uses the physical button to recover. Note in the result.

- [ ] **Step 3 — Commit results**

  ```bash
  git add scripts/native-stack/write-probe/phase2c/round2-results-*.csv \
          scripts/native-stack/write-probe/phase2c/round2-console-*.log
  git commit -m "phase-2c(probe): Round 2 visible toggle — Leopard 3 results"
  ```

---

## Group D — Aggregate + Phase 2 spec input

### Task 9: Finalise the permission map

**Files:**
- Modify: `docs/native-data-stack/permission-map-phase-2c-2026-05-27.md` (created in Task 5).

- [ ] **Step 1 — Add Round 2 column to the per-fid table**

  | category | name | dev | fid | round1_verdict | round2_verdict | takes_effect | notes |

- [ ] **Step 2 — Write the implications section**

  Three classes, with concrete counts:
  - **Direct write OK** (Round 1 ACCESSIBLE + Round 2 WRITE_TAKES_EFFECT): N fids. These can be driven from BYDMate by extending `AdbOnDeviceClient.WRITE_BARRIER_REGEX` to allow tx=6 explicitly. No daemon needed for them.
  - **Daemon required** (Round 1 DENIED or Round 2 WRITE_SILENT_FAIL / WRITE_EXC): N fids. These need the `aps_diplus`-style persistent shell-uid daemon, paired with BYDMate via local socket or AIDL broadcast.
  - **Banned / unsafe** (anything Round 2 surfaced as side-effecting outside intent): N fids. These never appear in Phase 2's user-facing surface.

- [ ] **Step 3 — Memory write-back**

  Update existing memories with concrete numbers:
  - `reference_autoservice_write_channel.md` → append "Round 1 + Round 2 results 2026-05-27: …".
  - `reference_autoservice_permission_model.md` → add per-fid classifications.
  - If surprises found → new `feedback_phase_2c_surprises.md`.

  Don't duplicate large tables in memory — link to `docs/native-data-stack/permission-map-phase-2c-2026-05-27.md`.

- [ ] **Step 4 — Commit**

  ```bash
  git add docs/native-data-stack/permission-map-phase-2c-2026-05-27.md \
          $HOME/.claude/projects/-Users-mac-andy-Projects-byd/memory/*.md
  git commit -m "phase-2c(docs): final permission map with implications for Phase 2 spec"
  ```

---

### Task 10: Hand-off to Phase 2 brainstorm

Mac-only, no operator. This task closes Phase 2c.

- [ ] **Step 1 — Invoke `superpowers:brainstorming` to start Phase 2 design**

  Input to brainstorm: the permission map (Task 9), `reference_aps_diplus_daemon.md`, `reference_autoservice_permission_model.md`, `feedback_system_sdk_safety.md`. Output: `docs/native-data-stack/spec-phase-2-2026-XX-XX.md`.

- [ ] **Step 2 — After spec is approved, invoke `superpowers:writing-plans`**

  Output: `docs/native-data-stack/plan-phase-2-2026-XX-XX.md`. Plan will pull from this Phase 2c permission map for the concrete fid list.

- [ ] **Step 3 — TaskList housekeeping**

  - Close Tasks #41, #42, #43 in TaskList — Phase 2c is the realisation of all three.
  - Keep Task #67 (Phase 2 — Native write channel) pending, now unblocked.

---

## Self-review

**Spec coverage:** This is a research plan, not a code plan, so "spec coverage" is replaced by "research question coverage":
- Q1 "Which fids does shell-uid `tx=6` accept?" → Tasks 4, 5.
- Q2 "Of those, which actually drive the hardware?" → Tasks 6, 7, 8.
- Q3 "What's the daemon vs. direct-write count?" → Task 9.
- Q4 "What goes into Phase 2 spec?" → Task 10.

**Placeholder scan:** every task has either a concrete command or a concrete artifact reference. No "TBD".

**Type consistency:** N/A (no Kotlin types).

**Safety review:** all live probes either no-op (Round 1) or operator-gated with revert (Round 2). Banned tier explicitly listed. Memory writeback ties findings back to durable storage.

**Open caveat:** if Round 1 returns *zero* ACCESSIBLE rows (worst case — all comfort writes are signature-permission gated), Group C (Tasks 6-8) collapses to a no-op and Task 9 directly concludes "Phase 2 = daemon, all fids". That outcome is still useful — it locks the design choice.

---

## Execution Handoff

Plan saved to `docs/native-data-stack/plan-phase-2c-2026-05-27.md`. Two execution paths:

1. **Sequential with Phase 1.5** *(recommended given operator availability)* — Phase 1.5 implementation runs subagent-driven on `feature/native-data-stack`. Phase 2c live snaps fit between implementation sessions; operator-in-car windows are ~30-45 min and need no concurrent code work.
2. **Parallel** — Phase 2c live snaps run today while Phase 1.5 subagents work on Tasks 1-5 (Foundation: db + entities + dao + migration). Group A/B of 2c (commit + ADB sanity + Round 1) can happen during a single short car session without interfering.

Which?
