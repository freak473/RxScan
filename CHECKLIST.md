# RxScan — Build Checklist

Shared to-do for getting v1 to the finish line. **How to use:** check the box when merged
to `main`; put your initials in _owner_; size is rough effort (S ≈ hours, M ≈ a day, L ≈ multi-day).
Keep this file honest — it's the source of truth for "who's doing what", CLAUDE.md is the deep context.

> Reality check (2026-07-23): the app captures a real photo, POSTs it to a real backend, and Verify
> renders the real extraction with flags intact. What's missing is the **stateful half** — nothing is
> saved, no login works for real, and **reminders don't actually fire yet**. That last one is the product.

---

## 🎯 Critical path to a working v1 (do these first)

The rest is polish. A user can't actually *use* RxScan until these three land:

1. [ ] **Reminders actually fire** — alarm scheduling + real notification/exact-alarm permission — _owner:__ · L
2. [ ] **Data survives restart** — persist confirmed meds + schedule (Room on device, or backend) — _owner:__ · M
3. [ ] **Real login** — OTP send/verify endpoint + app wired to it (or ship the "Skip" path only for v1) — _owner:__ · M

---

## ✅ Done (built + verified)

**Backend (Spring Boot / Maven / Java 21)**
- [x] Dual-DB architecture (engine + consumer), Flyway V1 migrations for both, both DBs provisioned
- [x] `/health` endpoint (reports both datasources)
- [x] `POST /extract` — multipart image → vision model → deterministic parser → flag-annotated JSON (sync)
- [x] Deterministic parser: frequency grammar, formulary match, duration, meal-timing, CDSCO flags
- [x] Vision providers: Gemini + OpenAI-compatible (Grok / Kimi), selectable via config; Grok default
- [x] Request validation (empty / type / 10MB) + error mapping (503 unavailable, 502 upstream)
- [x] Formulary loader
- [x] Unit tests (parser, controller, service, vision clients) — all mocked, **no live AI calls** (per CLAUDE.md)
- [x] **OTP / sign-in endpoint** — `/v1/auth/otp/request` + `/v1/auth/otp/verify`, blind-indexed phone, 30-day JWT, stub OTP `000000`; contract in `docs/api-contract-v1.md`
- [x] **Persist prescription** — `POST/PATCH /v1/prescriptions` + `GET ?since=`, encrypted payload blob per rx (DPDP); contract in `docs/api-contract-v1.md`

**Android (Compose)**
- [x] All 12 design screens built + verified on-emulator against the v3 prototype
- [x] Real CameraX capture + gallery upload
- [x] Verify wired to real `POST /extract` (repository + viewmodel + retrofit net layer)
- [x] DTO→domain mapping with CDSCO firewall intact (never shows a suggested value; freq-flag wins by priority)
- [x] Hard verify gate, flag inputs (empty, validated), editable "what we read" dialog
- [x] Ask-your-doctor native share sheet; persistent "silenced" banner on Today (PRD §6.4)
- [x] Custom fonts (Bricolage / Hanken / Kalam), 3 AA-contrast fixes

**Docs** — PRD v0.4.2, tech design v0.2.3, v3 UI prototype, PRODUCT.md, DESIGN.md

---

## 🚧 Pending

### Backend
- [ ] **Reminders / adherence API** — save schedule; log taken / snooze / skip — _owner:__ · M
- [ ] Client-key auth on `/extract` (currently open) + usage metering wired to engine DB — _owner:__ · M
- [ ] Retention + correction capture wired (schema exists, nothing writes to it) — _owner:__ · S
- [ ] Deployment target (runs on localhost only today) — _owner:__ · M

### Android
- [ ] **Alarm scheduling** — reminders actually fire at dose times — _owner:__ · L
- [ ] **Real permission requests** — POST_NOTIFICATIONS + exact-alarm (mocked inline now) — _owner:__ · S
- [ ] **Room cache / persistence** — meds live in `remember`, lost on process death; Today/Progress run on sample data — _owner:__ · M
- [ ] Sign-in / OTP wired to real backend (UI-only today — see `RxScanNav.kt`; backend contract is `docs/api-contract-v1.md`) — _owner:__ · M
- [ ] Error states: honest-failure (unreadable image), offline banner, toasts on save/deny (OTP error done) — _owner:__ · M
- [ ] Physical-device base URL — hardcoded emulator `10.0.2.2` in `Network.kt`; needs LAN IP / build flavor — _owner:__ · S
- [ ] Welcome-screen subcopy clips behind CTA on 320dp AVD (see CLAUDE.md "Watch") — _owner:__ · S

### Cross-cutting / product
- [ ] End-to-end save: Verify → confirmed meds → persisted schedule → Today reflects real state — _owner:__ · L
- [ ] **Phase 0 eval harness** — the actual accuracy risk; validate extraction on real prescriptions — _owner:__ · L
- [ ] Vision provider + paid key decision (free quota is scarce — see CLAUDE.md) — _owner:__ · S
- [ ] **Close Gupshup contract for OTP SMS** — vendor chosen (Gupshup, SMS only — no WhatsApp OTP for now); needs DLT sender-ID + template registration + account creds. `GupshupOtpSender` is coded and config-gated; until creds land, OTP is the static stub `000000` (`rxscan.otp.provider=stub`) — _owner:__ · S

### Release
- [ ] Signed release APK + distribution channel — _owner:__ · S
- [ ] Backend hosting + secrets management (vision key, DB creds) — _owner:__ · M

---

## 🤝 Collaborator setup (read before cloning)

Gotchas that'll cost your friend an afternoon otherwise:

- **JDK 21** required. CLI: `JAVA_HOME=/usr/local/opt/openjdk@21`. Android build uses Studio's bundled JBR 21.
- **Two Postgres DBs** must exist: `rxscan_engine` + `rxscan_consumer` (Postgres 16, brew). Flyway migrates each on boot.
- **Vision key** — set `rxscan.vision.api-key` (or `GEMINI_API_KEY`). Without it the vision bean isn't created and `/extract` 503s **by design**. Default provider is `grok` (needs an `xai-...` key).
- **App → backend URL** is `http://10.0.2.2:8080/` (emulator→host loopback) in `Network.kt`. Change to the host's LAN IP for a physical device.
- **Emulator**: this Mac is an 8GB Intel i5 — use the light AVD `rxscan_light`, not `Pixel_7` (it hangs). Launch command is in CLAUDE.md.
- **Build the app**: `JAVA_HOME=/usr/local/opt/openjdk@21 android/gradlew -p android assembleDebug`
- **Run the backend**: `cd backend && ./mvnw spring-boot:run` (or open in an IDE).
