# RxScan — build context

Scan a handwritten prescription → verified medication reminders. Android-first, India.
**The crux/moat is extraction + verification accuracy**, not OCR or reminders. Product is a
non-advisory *scribe* (stays outside India's SaMD/CDSCO regime); DPDP-compliant by design.

## Source of truth
- `docs/prescription-reminders-prd-v0_4_2.md` — product PRD (requirements closed)
- `docs/rxscan-tech-design-v0_2_2.md` — engineering design (two-plane architecture, DB choices)
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

## Current status (last updated 2026-07-16)
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

Not yet done: real camera, real extraction call, real OTP/backend, real POST_NOTIFICATIONS +
exact-alarm requests (mocked inline per design), alarm scheduling, Room cache, custom fonts
(system fonts; swap point `ui/theme/Type.kt` — Bricolage/Hanken/Kalam), error states beyond OTP
(honest-failure/offline), toasts on save/deny.

## Toolchain (this machine)
- **Android build JDK**: Android Studio bundled JBR 21 (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`).
- **CLI builds**: `JAVA_HOME=/usr/local/opt/openjdk@21` (openjdk@21 via brew) — now persisted in
  `~/.zshrc` + `~/.bash_profile`, so new shells default to Java 21. Java 8 still at `/Library/...`
  (system) — user was going to `sudo rm` it.
- **Android SDK**: `~/Library/Android/sdk` (only platform `android-36`, build-tools `36.0.0` installed).
- **Postgres 16**: installed via brew, running (`brew services`). For the backend phase.
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
1. Custom fonts (Bricolage / Hanken / Kalam) via downloadable Google Fonts or bundled TTFs.
2. Remaining UI states: honest-failure (unreadable image), offline banner, PRN/SOS, minor-consent.
3. Then wire real behavior, or pivot to Phase 0 eval harness (the actual accuracy risk) / Phase 1 backend.
