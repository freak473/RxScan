# Reminder-firing plane — design

**Date:** 2026-07-23 · **Status:** approved (brainstorm with user)
**Sources:** PRD v0.4.2 §6 steps 4–6, §8; tech design v0.2.3; api-contract-v1.md

Scan→verify→save already persists confirmed meds (opaque `payloadJson` in Room +
server) and meal times (DataStore + `PUT /v1/me/preferences`). Nothing schedules,
fires, or sounds. This slice builds the full PRD reminder plane.

## Scope

**In:** real POST_NOTIFICATIONS request · exact-alarm Settings step (Android 12+) ·
chained exact-alarm scheduling with AC/PC offsets · boot/time-change rescheduling ·
grouped discreet notification with Taken/Snooze 30m/Skip + system-default sound ·
adherence log in Room, synced inside the opaque payload · course-end notice +
auto-stop · Today/Progress rewired to real doses · notification-tap routing to Today.

**Out (deferred):** battery-optimization exemption prompt (PRD: contextual, later) ·
in-app "show names on lock screen" setting (Android channel settings already allow
it) · user-adjustable AC/PC offsets (constant ±30 min; no UI for it in the design) ·
bedtime slot (dropped at the wire layer — existing PayloadMapper ceiling) ·
WorkManager fallback (replaced, see Scheduling).

## Architecture

New package `com.rxscan.app.reminders`:

| Unit | Job |
|---|---|
| `DosePlan.kt` | Pure functions, no Android deps. Parse `payloadJson` (+ meal times) → dose times. Emits `nextEvent(now)` (next dose fire or course-end notice), `dueAt(now)`, `todaysDoses()`. |
| `ReminderScheduler.kt` | Single entry point `reschedule(context)`: load all saved prescriptions from Room + meal times from DataStore, compute next event across them, arm **one** alarm. Called from: save, alarm fire, boot, time/timezone change, app open, meal-time edit. |
| `DoseAlarmReceiver` | Alarm fires → recompute what's due now (never trusts stale extras), post grouped notification, `reschedule()`. |
| `NotificationActionReceiver` | Taken/Snooze/Skip → adherence events in Room, dismiss. Snooze arms a one-off alarm +30 min that re-posts the same doses (ids+names in extras — short-lived, staleness acceptable). |
| `BootReceiver` | `BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED` → `reschedule()`. |

Manifest: `POST_NOTIFICATIONS`, `SCHEDULE_EXACT_ALARM` (not `USE_EXACT_ALARM` —
Play policy reserves it for alarm-clock apps), `RECEIVE_BOOT_COMPLETED`; receivers
`exported="false"` except boot (system broadcast).

## Dose model (`DosePlan`)

- **Slot → clock:** morning→breakfast, afternoon→lunch, night→dinner.
- **AC/PC offsets:** `before_food` = meal − 30 min, `after_food` = meal + 30 min,
  null meal timing = at meal time. Constant ±30 for now.
- **Course window:** day 1 = confirm day (`confirmedAt` date). Last day =
  day 1 + `durationDays` − 1. `durationDays == null` and not PRN ⇒ ongoing, never
  expires. PRN meds are never scheduled.
- **Dose identity (computed, never stored):** `"$rxLocalId:$medIndex:$date:$slot"`
  (e.g. `1:0:2026-07-23:night`). No dose table exists.
- **`nextEvent(now)`:** earliest of (a) next instant strictly after `now` with ≥1
  due dose, grouped, (b) next course-end notice — day before a med's last day, at
  the dinner slot time: "Your <name> course ends tomorrow".
- **`dueAt(now)`:** doses with fire time in `[now − 90 min, now]` and no adherence
  event yet. If an alarm is delivered later than the slack, nothing posts — the
  dose still shows as due/missed on Today.
- **Auto-stop is free:** past the last dose, `nextEvent` returns null → nothing armed.

## Scheduling (approach A — chained next-dose alarm)

One alarm armed at a time. `setExactAndAllowWhileIdle` when
`canScheduleExactAlarms()`, else `setAndAllowWhileIdle` (inexact; Android batches,
typically ≤15 min late) + one-line accuracy note on Today.

**Deliberate PRD deviation (user-approved):** the PRD names "WorkManager windows"
as the exact-denied fallback; the inexact alarm is the same chain with one flag
flipped and honors the intent (degraded-but-working delivery + accuracy note).

Known ceiling (`ponytail:` comment at the entry point): force-stop pauses the
chain until next app-open/boot — same weakness as every reminder app.

## Permissions flow

`NotifPermScreen` primer keeps its visuals; "Allow" launches the real
`RequestPermission(POST_NOTIFICATIONS)` on API 33+ (≤32 auto-grants and skips
through — the API-30 dev emulator takes this path). Result drives the existing
hoisted `notifAllowed`; Today's silenced banner re-checks
`areNotificationsEnabled()` on resume so returning from Settings heals it.

Then the separate, skippable exact-alarm step: on Android 12+ with
`canScheduleExactAlarms() == false`, a card offers the
`ACTION_REQUEST_SCHEDULE_EXACT_ALARM` Settings deep-link; Skip proceeds.
**Denial never blocks: save + schedule always happen.**

## Notification

- Channel `dose_reminders`, high importance, created at first schedule,
  **system-default sound** (user-editable per-channel in Android settings).
- One grouped notification per fire time. Unlocked: med names
  ("Augmentin 625 · Pantocid 40"). Lock screen (`setPublicVersion`): discreet —
  "Night medicines · 2 due" — count, never names.
- Actions **Taken · Snooze 30m · Skip** act on every dose in the group; per-med
  granularity lives in the Today dose sheet.
- Notification id derives from `date:slot` so a snooze re-post replaces the original.
- Tap → MainActivity with a `dest=today` extra; nav starts at Today when a saved
  Rx + token exist (also fixes cold-start-always-at-welcome).
- Course-end notice: no actions, posted on a second default-importance channel
  `course_updates` (channel importance is fixed per-channel on API 26+, so it
  can't ride the high-importance dose channel without ringing like a dose).

## Adherence log + sync

Room table `adherence_events`: `doseId`, `medName`, `action`
(taken/snoozed/skipped), `at` (ISO-8601), `pendingSync`. Written by both the
notification actions and the Today dose sheet. Sync: on app open and after save,
pending events merge into an `adherence[]` array inside the opaque payload →
`PATCH /v1/prescriptions` → mark synced. Offline ⇒ events wait. No new backend
work: the server stores the payload opaquely (user-approved choice; a dedicated
adherence endpoint waits for v2 caregiver/analytics needs).

## Today + Progress rewire

Today builds from `DosePlan.todaysDoses()` × the adherence log
(upcoming/due/taken/skipped/snoozed); the dose sheet writes real events. Progress
day-chips, adherence bar, and the doctor report compute from real events over the
course window. No saved Rx (dev flow) ⇒ Today falls back to MockData rendering —
removed when real extraction lands.

## Testing

Per CLAUDE.md: no live-API tests. `DosePlan` is pure → JVM unit tests: slot
mapping, AC/PC offsets, course window incl. ongoing + auto-stop, PRN exclusion,
next-fire selection across multiple prescriptions, deterministic dose ids, due
slack, course-end notice timing. Plus an adherence-merge (payload PATCH shape)
test. Alarm/receiver/permission paths verified manually on the emulator
(`adb shell am broadcast` for boot; device-clock nudges for firing).
