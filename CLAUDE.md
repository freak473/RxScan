# RxScan — build context

Scan a handwritten prescription → verified medication reminders. Android-first, India.
**The crux/moat is extraction + verification accuracy**, not OCR or reminders. Product is a
non-advisory *scribe* (stays outside India's SaMD/CDSCO regime); DPDP-compliant by design.

## Source of truth
- `docs/prescription-reminders-prd-v0_4_2.md` — product PRD (requirements closed)
- `docs/rxscan-tech-design-v0_2_3.md` — engineering design (two-plane architecture, DB choices)
- `docs/RxScan-v2-design-v3.html` — interactive UI prototype (the visual design system)

## Repo layout
- `android/` — Kotlin + Jetpack Compose app (this is where active work is)
- `backend/` — Spring Boot service (not started yet)
- `docs/` — the three design docs above

## Non-negotiable product invariants (enforce in code)
- **Flag, don't correct** (CDSCO): never pre-fill/suggest a value for an anomalous field —
  re-check flags show an EMPTY input. No `suggested_value` anywhere.
- **Verify is a hard gate**: every medicine must be actively confirmed before scheduling.
- **Non-daily frequencies force a confirm** (weekly-misread-as-daily is the highest-harm error).
- **Non-advisory copy**: "your prescription says…", never "you should take…". No inferred indications.
- **Data minimisation**: phone is the only PII; prescription record encrypted server-side (later phases).

## After every commit — update this file
- **Every git commit must be followed by a CLAUDE.md update**: refresh "Current status" (+ its
  date), and add/adjust any rules or context the change introduced. A commit without a CLAUDE.md
  update is an unfinished commit.

## Backend IDs — sequential ids are internal-only
- `app_user.user_id` is a sequential BIGINT identity — fine as an internal PK/FK, but it is
  enumerable. **Never expose it in client-facing API paths, URLs, or tokens** (`/users/42` leaks
  user count and invites IDOR probing). Anything the client sees must use an opaque id
  (UUID/random token). Same rule for any future sequential id.

## Backend testing — no live AI calls
- **Never write automated tests that hit a real vision/AI API.** Free provider quota is limited and
  scarce. Mock the provider instead (`MockRestServiceServer`, canned response bodies) and unit-test
  the request shape, `parseResponse`, and error mapping. Real extraction accuracy is validated
  **manually via the app** (capture → `POST /extract`), not in the test suite.

## Current status (last updated 2026-07-24)
**Backend consumer plane — slice A DONE:** all 8 consumer endpoints live and smoke-tested against
the real dev DBs — `/v1/auth/otp/request`, `/v1/auth/otp/verify`, `PUT /v1/me/consents`,
`PUT`/`GET /v1/me/preferences`, `POST`/`PATCH /v1/prescriptions`, `GET /v1/prescriptions?since=`.
Schema reworked (`app_user`→`users`, BIGINT identity ids, `user_consent` + `user_preference`
tables, `created_at`/`updated_at` + `set_updated_at()` trigger everywhere, both DBs re-provisioned).
Stub OTP `000000` (`GupshupOtpSender` config-gated — SMS only, no WhatsApp, real creds pending);
JWT sub = `users.public_id`, opaque `payload` round-tripped verbatim. FE-facing contract doc:
`docs/api-contract-v1.md` (supersedes the design spec as the day-to-day reference; spec is still
`docs/superpowers/specs/2026-07-23-consumer-api-v1-design.md`, plan is
`docs/superpowers/plans/2026-07-23-consumer-api-v1-slice-a.md`). Tech design at **v0.2.3**
(`docs/rxscan-tech-design-v0_2_3.md`). Use system `mvn` on this machine, never `./mvnw` (broken —
see Toolchain). Post-commit hook in `.claude/settings.local.json` enforces the CLAUDE.md-update
rule. FE wiring to the consumer API in progress (docs/superpowers/plans/2026-07-23-consumer-api-v1-fe-wiring.md; worktree `rxscan-fe`, branch `fe/consumer-wiring` — Tasks 1–6 committed there, resume at Task 7: manual emulator smoke).

**PENDING — vision API key (blocks `/extract`):** no real xAI key is configured anywhere; a
commented TODO slot sits in `backend/src/main/resources/application.properties`
(`rxscan.vision.api-key=xai-REPLACE-ME`). Until the user pastes the real key there (or exports
`RXSCAN_VISION_API_KEY`), `/extract` 503s by design and the capture→verify flow can't be smoke-
tested end-to-end. Key is deliberately never committed.

**FE wiring (this branch, `fe/consumer-wiring`) — progress:** Tasks 1–6 of
`docs/superpowers/plans/2026-07-23-consumer-api-v1-fe-wiring.md` committed (gradle deps ·
DTOs/APIs/interceptor/PayloadMapper+test · RxScanStore · Room+PrescriptionRepository ·
SyncRepository · screens/RxScanNav rewired to the real API — OTP demo branch removed, `000000`
is the real dev stub). **Resume at Task 7** (final build + manual emulator smoke vs local backend).

**Reminder plane — spec approved (2026-07-23):** full PRD reminder-firing plane designed and
approved (branch `feat/reminder-plane`): chained next-dose exact alarm (one armed at a time;
inexact-alarm fallback replaces the PRD's WorkManager fallback — user-approved deviation), real
POST_NOTIFICATIONS + skippable exact-alarm Settings step, grouped discreet notification
(Taken/Snooze 30m/Skip, system-default sound, `dose_reminders` high + `course_updates` default
channels), adherence log in Room synced inside the opaque payload (no new backend endpoints),
course auto-stop, Today/Progress rewired to real doses. Spec:
`docs/superpowers/specs/2026-07-23-reminder-plane-design.md`. Plan:
`docs/superpowers/plans/2026-07-23-reminder-plane.md` (7 tasks, TDD, exact code). Plan Task 1 (DosePlan) done — pure dose arithmetic with 13 JVM tests (Review fix: AC/PC offset now day-carries past midnight). Plan Task 2 (adherence Room slice) done — AdherenceEventEntity + AdherenceDao + AdherenceRepository, Room v2 migration, PrescriptionDao.all()/updatePayload(), build clean.
Plan Task 3 (alarm chain + notification) done — `reminders/Channels.kt`, `DoseNotifier.kt`,
`ReminderScheduler.kt`, `Receivers.kt` (DoseAlarmReceiver/NotificationActionReceiver/BootReceiver,
all FLAG_IMMUTABLE, goAsync), manifest wired (POST_NOTIFICATIONS/SCHEDULE_EXACT_ALARM/
RECEIVE_BOOT_COMPLETED + 3 receivers), `ReminderScheduler.reschedule()` called from
MainActivity.onCreate and RxScanNav's meal-times save + notif-perm save points. Build clean.

**Phase 2 (Android) — UI pass COMPLETE: all 12 design screens built + verified on-emulator
against `RxScan-v2-design-v3.html` (screenshot walkthrough of the full flow).**

Flow (matches design's canonical order):
welcome → consent → capture → extracting → verify → mealtimes → signin → otp → notifperm
→ today (⇄ lock preview, ⇄ progress). Nav: `ui/RxScanNav.kt`; phone hoisted there for signin→otp.

Mock data = the design's exact demo meds (`data/MockData.kt`): Augmentin (clean), Pantocid
(strength unreadable → flag), Ascoril LS (duration unclear → numeric flag), Dolo 650 (PRN/SOS).
Verified working on-device: hard gate (disabled until 4/4, bypass tap does nothing), confirm →
collapsed green bar + Edit, flag inputs (empty, validated Save: strength ≥2 chars, days 1–60
numeric keypad), resolved duration renders "5 days · ends Wed, 15 Jul", ask-your-doctor opens the
REAL native share sheet with the pre-written question, OTP countdown → resend, mealtime ± steppers,
dose sheet (Taken/Snooze/Skip) via ModalBottomSheet, adherence bar, progress day-chips, report share.

User-feedback fixes (2026-07-16, after hands-on testing): verify cards are now editable —
pencil icon → "Edit what we read" dialog (name + strength free text, prefilled with the current
displayed reading; formulary autocomplete comes with the backend). Flag boxes stay visible
(and revisable) until the card is confirmed, with a "✓ Saved" state, so Ask-your-doctor no longer
disappears after saving a value. Flag input is seeded only with the USER's own prior entry —
never a system value (CDSCO invariant intact).

Custom fonts DONE (2026-07-18): Bricolage Grotesque / Hanken Grotesk (variable TTFs, weight-pinned
via FontVariation) + Kalam Regular/Bold bundled in `res/font`, wired in `ui/theme/Type.kt`; heading
Texts across all screens carry `DisplayFamily` explicitly (inline styles inherit body face otherwise).
Verified on-emulator. Also fixed 3 AA contrast fails (Faint→Muted: verify disclaimer, flag placeholder,
"FROM YOUR PAPER" label). PRODUCT.md + DESIGN.md now exist at repo root (impeccable init/document).

Not yet done: real camera, real extraction call, real OTP/backend, real POST_NOTIFICATIONS +
exact-alarm requests (mocked inline per design), alarm scheduling, Room cache, error states beyond OTP
(honest-failure/offline), toasts on save/deny. Watch: welcome-screen subcopy clips behind the CTA on
the 320dp-wide light AVD (Kalam's tall metrics grew the rx card) — candidate for /impeccable adapt.

## Toolchain (this machine)
- **Android build JDK**: Android Studio bundled JBR 21 (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
- **CLI builds**: `JAVA_HOME=/usr/local/opt/openjdk@21` (openjdk@21 via brew) — now persisted in
  `~/.zshrc` + `~/.bash_profile`, so new shells default to Java 21. Java 8 still at `/Library/...`
  (system) — user was going to `sudo rm` it.
- **Android SDK**: `~/Library/Android/sdk` (only platform `android-36`, build-tools `36.0.0` installed).
- **Postgres 16**: installed via brew, running (`brew services`). For the backend phase.
- **Backend builds**: use system `mvn` (3.9.16) — `backend/mvnw` is broken on this machine (its
  wget links a deleted brew dylib and the wrapper tries to re-download Maven).
- **Node**: brew node 26.5.0 (2026-07-18), fully brew-linked; old 2019 node-pkg remnants removed.
  npm global prefix is the Cellar keg, so `npm -g` bins land in `Cellar/node/*/bin` (prefer `npx`).
- Docker Desktop: optional / user's call (redundant with native Postgres for v1).

### Build the app from CLI
```
JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android assembleDebug
```
APK → `android/app/build/outputs/apk/debug/app-debug.apk`.
Or just open `android/` in Android Studio (uses its bundled JBR 21) → Run.

### Emulator (this Mac is an 8GB 2018 Intel i5 — heavy AVDs HANG)
The user's original `Pixel_7` AVD uses `android-37.1 google_apis_playstore` (heaviest possible) and
never finishes booting. Use the **light AVD** instead — AOSP API 30, Nexus 4 (768×1280), software GPU:
```
# AVD name: rxscan_light  (already created)
~/Library/Android/sdk/emulator/emulator -avd rxscan_light \
  -gpu swiftshader_indirect -no-snapshot -no-boot-anim -memory 1536 -cores 2 &
# wait for boot, then:
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB install -r android/app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start -n com.rxscan.app/.MainActivity
$ADB exec-out screencap -p > shot.png   # to inspect the UI
```
Boots in ~30s and runs the app fine. cmdline-tools were copied (not symlinked) to
`~/Library/Android/sdk/cmdline-tools/latest` so `avdmanager` detects the right SDK root.

### Versions (android/gradle/libs.versions.toml)
Gradle 8.11.1 (wrapper) · AGP 8.7.3 · Kotlin 2.0.21 · Compose BOM 2024.12.01 · compileSdk/target 36 · minSdk 26.
`android.suppressUnsupportedCompileSdk=36` set because only the SDK-36 platform is installed.

## Next steps (candidates)
1. Remaining UI states: honest-failure (unreadable image), offline banner, PRN/SOS, minor-consent.
2. Welcome-screen small-screen clipping fix (see Watch above).
3. Then wire real behavior, or pivot to Phase 0 eval harness (the actual accuracy risk) / Phase 1 backend.
