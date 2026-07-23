# Handover — Reminder-firing plane (branch `feat/reminder-plane`)

**Written:** 2026-07-24 · for a fresh Claude session (or human) to resume.
**Branch:** `feat/reminder-plane` (pushed to `origin`, i.e. `git@github.com:freak473/RxScan.git`).
Check it out: `git fetch origin && git checkout feat/reminder-plane`.

## What this branch is building

Saved prescriptions currently **do not** schedule, fire, or sound any reminder. This
branch builds the whole reminder-firing plane so a confirmed prescription actually
rings on time with an actionable notification.

- **Spec (approved):** `docs/superpowers/specs/2026-07-23-reminder-plane-design.md`
- **Plan (7 tasks, TDD, full code in each task):** `docs/superpowers/plans/2026-07-23-reminder-plane.md`
- This handover is the **source of truth for progress** — the SDD ledger at
  `.superpowers/sdd/progress.md` is git-ignored scratch and is NOT on the remote.

## Architecture in one breath (so you don't re-derive it)

**Chained next-dose alarm.** A pure `DosePlan` computes the *next* reminder event;
`ReminderScheduler.reschedule()` arms exactly **one** `AlarmManager` alarm for it; when
it fires, the receiver posts the grouped notification and immediately re-arms the next.
Boot / time-change / app-open / save / meal-time-edit all just call `reschedule()`.
Doses are **computed, never stored** — dose id is deterministic
`"$rxLocalId:$medIndex:$date:$slot"` (e.g. `1:0:2026-07-24:night`). Adherence events are
the only new table; they sync **inside the existing opaque prescription payload** via
`PATCH /v1/prescriptions` (no new backend endpoints).

**Deliberate, user-approved deviation from the PRD:** the exact-alarm-denied fallback is
the *same* chain with an inexact alarm (`setAndAllowWhileIdle`) + an in-app accuracy note,
NOT the PRD's "WorkManager windows". Don't "fix" this back.

**Invariants that bind every task** (from CLAUDE.md + spec):
- Build/test ONLY via `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android …`
  (never `./mvnw`, never bare `gradlew`). Run from repo root `/Users/ankitjain/rxscan`.
- No live AI/backend calls in automated tests. `DosePlan` is pure → JVM unit tests;
  alarms/notifications are validated **manually on the emulator** (Task 7).
- CDSCO copy: non-advisory ("your prescription says…", never "take your medicine"),
  no suggested values, PRN meds never scheduled.
- Every commit must also touch CLAUDE.md (a post-commit hook rejects commits that don't) —
  bump the "last updated" date AND add a one-line status note.
- minSdk 26 / target 36. Guard API 31+ (`canScheduleExactAlarms`) and 33+
  (`POST_NOTIFICATIONS`) with `Build.VERSION.SDK_INT` checks. The dev emulator is API 30,
  so it auto-grants notifications and skips the system dialog.

## Progress — DONE (all reviewed clean, committed on this branch)

Commit range: `41b261b` (plan) → `2f1e060` (current HEAD). `git log --oneline 41b261b..HEAD`:

| Task | Commits | State |
|---|---|---|
| **1 · DosePlan** (pure dose arithmetic + 13 JVM tests) | `839dff0`, fix `fd7f638` | ✅ review-clean. Review caught a midnight day-carry bug (AC/PC offset wrapped `LocalTime` at midnight); fixed — arithmetic now on `LocalDateTime`. |
| **2 · Room adherence slice** (`adherence_events` table, DAO, repo, DB v2 `MIGRATION_1_2`) | `ad9d1d4` | ✅ review-clean. Migration is additive-only — v1 installs keep their `prescriptions` data. |
| **3 · Alarm/notification plumbing** (Channels, DoseNotifier, ReminderScheduler, 3 receivers, manifest, MainActivity + RxScanNav call sites) | `e5c7923`, fix `2f1e060` | ✅ review-clean. Review caught an unguarded receiver coroutine (crash on the alarm path would kill the chain); fixed with a try/catch. Also tightened course-notice lock-screen discretion. |

New/changed code lives in `android/app/src/main/java/com/rxscan/app/reminders/`
(`DosePlan.kt`, `Channels.kt`, `DoseNotifier.kt`, `ReminderScheduler.kt`, `Receivers.kt`)
+ `data/local/AdherenceEventEntity.kt`, `AdherenceDao.kt`, `data/AdherenceRepository.kt`
+ edits to `RxScanDatabase.kt`, `PrescriptionDao.kt`, `PrescriptionRepository.kt`,
`AndroidManifest.xml`, `MainActivity.kt`, `ui/RxScanNav.kt`. Both suites build; unit tests pass.

## Progress — REMAINING (Tasks 4–7 in the plan)

Full code for each is in the plan file. Summary of what's left:

- **Task 4 · Real permissions.** Replace `NotifPermScreen`'s mock dialog with the real
  `RequestPermission(POST_NOTIFICATIONS)` launcher (API 33+; ≤32 auto-grants) + the
  skippable exact-alarm Settings deep-link. Make `TodayScreen`'s silenced banner heal on
  resume (`areNotificationsEnabled()`), and show the inexact-alarm accuracy note.
- **Task 5 · Adherence sync.** Add `AdherenceEntryDto` + `MedsPayloadDto.adherence` (additive,
  default null) + `withAdherence()` merge (dedup) + `SyncRepository.pushAdherence()`
  (PATCH), pushed opportunistically on app open. Has a JVM merge/dedup test (TDD).
- **Task 6 · Today/Progress on real data.** New `reminders/UiData.kt` builds Today's dose
  list + Progress day-chips + doctor-report text from `DosePlan` × the adherence log;
  dose-sheet actions write real events; cold-start routes returning users to Today. Screens
  keep their MockData as the no-saved-Rx dev fallback.
- **Task 7 · Emulator smoke (MANUAL).** The payoff: install on the `rxscan_light` AVD, walk
  the flow, set a meal time a few minutes out, and **hear a dose actually ring**. Verify
  notification actions write through, boot re-arms the chain (`adb shell dumpsys alarm`),
  and lock-screen discretion. Record results in CLAUDE.md.

**Nothing here needs the vision API key** — that only blocks `/extract`. Use the app's
built-in MockData meds (Augmentin/Pantocid/Ascoril/Dolo) to reach the verify→save→schedule
path without a real scan.

## Minor findings deferred to the FINAL whole-branch review

(Recorded here because the ledger won't travel with the push. None block; the final review
triages which to fix before merge.)

- **T1:** `DosePlan.slotTime` maps an unknown `slot` string silently to dinner (no loud fail).
- **T2:** the CLAUDE.md "last updated" date wasn't bumped on the Task 2 commit (later commits fixed the date; note only).
- **T3:** `DoseNotifier` action request-codes use `id*10+req` (not collision-proof, but
  astronomically unlikely); `DoseAlarmReceiver` handles `ACTION_DOSE_FIRE` via the `else`
  branch rather than an explicit `when` arm (fragile if a 4th action is added).

## How to resume (SDD loop)

This branch was built with **superpowers:subagent-driven-development**. To continue:

1. Invoke that skill. It dispatches a fresh implementer subagent per task, then a task
   reviewer, then fixes, per task.
2. Extract each task's brief with the skill's `scripts/task-brief`:
   `scripts/task-brief docs/superpowers/plans/2026-07-23-reminder-plane.md N`.
   **Rename the output** `task-N-brief.md` → `rp-task-N-brief.md` (a parallel backend/FE loop
   used the same script and the un-prefixed names collide). Task 4's brief was extracted as
   `.superpowers/sdd/rp-task-4-brief.md` locally, but that dir is git-ignored — just re-run
   the script after checkout.
3. Review packages: `scripts/review-package BASE HEAD` where BASE is the commit before the
   task. **Resume base = `2f1e060`** (current HEAD) for Task 4.
4. Model guidance that worked: implementers on `haiku` for pure-transcription tasks (the plan
   carries full code), `sonnet` for integration tasks (T3, T6); reviewers on `sonnet`.
5. After all 7 tasks: dispatch the final whole-branch review
   (superpowers:requesting-code-review, most capable model), triage the Minors above, then
   superpowers:finishing-a-development-branch.

## Toolchain (this machine — from CLAUDE.md)

- Android build: `JAVA_HOME=/usr/local/opt/openjdk@21`. APK →
  `android/app/build/outputs/apk/debug/app-debug.apk`.
- Emulator (8GB Intel Mac — heavy AVDs hang; use the light one):
  ```
  ~/Library/Android/sdk/emulator/emulator -avd rxscan_light \
    -gpu swiftshader_indirect -no-snapshot -no-boot-anim -memory 1536 -cores 2 &
  ADB=~/Library/Android/sdk/platform-tools/adb
  $ADB install -r android/app/build/outputs/apk/debug/app-debug.apk
  $ADB shell am start -n com.rxscan.app/.MainActivity
  ```
