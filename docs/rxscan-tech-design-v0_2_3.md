# Technical Design: RxScan

### Scan a prescription → verified medication reminders

**Version:** 0.2.4-draft (engineering handoff — requirements closed)
**Pairs with:** `prescription-reminders-prd-v0_4_2.md` (product), `RxScan-v2-design-v3.html` (UX)
**Scope:** v1 backend + Android client + partner-ready extraction API
**Last updated:** 23 July 2026

> **Changed in v0.2.4-draft:** Three architecture changes. **(A) Single database:** the two-plane
> engine/consumer split (§1, §5, §6) is **replaced for v1** by one `rxscan` Postgres database
> holding only `users`, `user_consent`, `user_preference`, `prescription`, `adherence_event`. The
> engine-plane tables (`extraction_job`, `formulary_sku`, `correction`, `retained_item`,
> `client_key`, `usage_meter`, `consent_provenance`) are **dropped**; extraction is now
> **stateless** (image → parse → return, nothing persisted server-side) and formulary matching is
> **disabled**. This is a deferral, not an abandonment — the engine/consumer split and
> platformisation (partner API, metering, retained eval set) return when the product
> platformises; see the "deferred" call-outs threaded through §1, §5, §6, §7, §8. **(B)
> Account-first:** the app flow is now phone entry → OTP (mocked `000000` in dev) → consents →
> capture → extracting → verify → mealtimes → notif-permission → dashboard, with **persistent
> login** (a stored session skips straight to the dashboard; logout clears it) — see PRD §6 step 1.
> **(C) Vision provider:** the vision-extraction call runs behind a pluggable
> `VisionExtractionClient` interface (`claude`/`gemini`/`grok`/`kimi`/`openai`); the current default
> is **Anthropic Claude** (`claude-sonnet-5`). One vision call + the deterministic parser is
> unchanged (§2.3). Encryption model, consent capture, and data-minimisation posture are
> unchanged.
>
> **Changed in v0.2.3 (consumer API v1 slice — aligns with `docs/superpowers/specs/2026-07-23-consumer-api-v1-design.md`):**
> Consumer schema reworked: `user` → **`users`** with **BIGINT identity `user_id` (internal-only — never client-visible, per the sequential-id rule)** plus **`public_id` UUID** as the JWT `sub` and any client-facing id. New tables: **`user_consent`** (append-only; FE holds consents locally pre-login and uploads them in the OTP-verify call) and **`user_preference`** (one encrypted FE-owned blob per user — meal times, toggles; server never parses it). Every table in **both** DBs now carries `created_at`/`updated_at`, maintained by a `set_updated_at()` trigger. Contract: `otp/verify` carries the consent list; added `PUT /v1/me/consents`, `PUT/GET /v1/me/preferences`. Slice A ships **JWT-only (no refresh token)**; OTP delivery via provider strategy — stub `000000` default, `GupshupOtpSender` ready (SMS only, no WhatsApp OTP).
>
> **Changed in v0.2.2 (aligns with PRD v0.4.2):** Added the Android permission strategy — POST_NOTIFICATIONS (runtime, API 33+) requested with a primer after OTP at the save moment; SCHEDULE_EXACT_ALARM via `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` Settings deep-link as skippable step two, with WorkManager-window fallback + in-app warning on decline; battery-exemption contextual, never at onboarding; denial-state banner on Today; notification channel "Dose reminders" (IMPORTANCE_HIGH) created at first schedule; behavior API-level gated (≤12 auto-grants).
>
> **Changed in v0.2.1 (aligns with PRD v0.4.1):** Q10 closed — account at save; pre-login scans device-metered; unauthenticated-verify state and OTP-at-save fallbacks specified. Cost section updated with the restored ₹1/scan planning figure.
>
> **Changed in v0.2 (aligns with PRD v0.4):** the local-only storage model is superseded. The user now has a **phone-OTP account**; the **confirmed prescription record is stored server-side, encrypted, keyed to a pseudonymous `userId`**; the **only PII is the phone number** (encrypted + keyed-HMAC blind index). The **server is the system of record**; the device keeps a **local cache (Room)** so reminders fire offline. The **extraction engine stays client-keyed and identity-free**, so the platform asset stays separable. **Encryption model resolved: envelope (server-decryptable) under a KMS/HSM master key** — recovers on lost device, and sufficient under DPDP Rule 6 (§9 Q9).

> This doc translates the PRD into an implementable system. Three constraints are load-bearing on almost every decision below and are called out wherever they bite:
> - **Single database, users-only (v1):** one `rxscan` Postgres database holds `users`, `user_consent`, `user_preference`, `prescription`, `adherence_event`. Extraction is stateless and formulary matching is disabled. The v0.4/v0.2.2-era **two-plane split** (a client-keyed, identity-free *engine plane* for extraction/eval-set/formulary, isolated from a *consumer plane* holding the user identity) is **deferred, not abandoned** — it returns when the product platformises (partner API, metering, a retained eval set). Everywhere this doc still describes the two-plane target design, it's marked **(deferred)**.
> - **Encrypt everything identity-linked (DPDP §3.2):** all prescription data is encrypted at rest under per-user envelope encryption; the phone number is the sole PII, stored encrypted with a blind index. Encryption is the safeguard that makes server-side health-data storage defensible.
> - **Flag, don't correct (CDSCO §3.1):** the data model must never persist or transmit a *suggested* value for an anomalous field. A "correction" is a user action against an empty input, not a system output.

---

## 1. System context

**v1 is a single backend, single database.** The app authenticates a user by phone-OTP *first* (PRD §6 step 1, persistent login), then calls a stateless extraction endpoint (image → parse → return, nothing persisted) and, once verified, saves the confirmed record into the one Postgres database.

```
                       ┌───────────────────────────────────────────────┐
                       │              RxScan Backend                   │
                       │        single `rxscan` Postgres database      │
                       │                                                │
  ┌────────────┐       │  ┌──────────────┐    ┌─────────────────────┐  │
  │  Android   │──────▶│  │ Extraction   │───▶│ Vision LLM call +   │──┼──▶ LLM (DP,
  │  app       │◀──────│  │ endpoint     │    │ deterministic parser│  │    Claude default)
  └─────┬──────┘       │  │ (stateless)  │    │ (§2.3)              │  │
        │              │  └──────────────┘    └─────────────────────┘  │
        │              │  no formulary service, no job queue — nothing  │
        │ phone-OTP    │  persisted by this call (image → parse → return)│
        │  + JWT       │                                                │
        │ (sign-in     │  ┌──────────────┐    ┌─────────────────────┐  │
        │  first)      │  │ Auth service │    │ users / user_consent│  │
        └─────────────▶│  │ (phone+OTP)  │    │ / user_preference / │  │
  ┌────────────┐       │  └──────────────┘    │ prescription /      │  │
  │ Room cache │◀─────▶│                       │ adherence_event     │  │
  │ on device  │       │  only PII = phone     │ ENCRYPTED, keyed    │  │
  └────────────┘       │  (encrypted + blind   │ userId (envelope    │  │
                       │  index)               │ enc / KMS)          │  │
                       │                       └─────────────────────┘  │
                       └───────────────────────────────────────────────┘

  Device-to-doctor share (WhatsApp/SMS/email) NEVER transits the backend.
  (Deferred) A separate client-keyed engine plane + partner lane returns with platformisation.
```

**v1 reality:** one database, no user-anonymous engine store. Sign-in (phone + OTP) happens first; a stored session (persistent login) skips straight to the dashboard on relaunch. The extraction endpoint is stateless and, today, has no client-key or JWT check of its own (the app is the only caller, and every call happens to occur inside a logged-in session, but that isn't enforced server-side yet — see §4). Formulary matching is disabled; the result schema still carries a `formulary_id` field, just unpopulated until the service returns. The **confirmed prescription record is stored server-side, encrypted, under a pseudonymous `userId`**, in the same database as the account/consent/preference rows. The **server is the system of record**; the device holds a **Room cache** so reminders fire offline, and queues writes (new schedules, adherence events) for push on reconnect. Delete/export operate by `userId`.

**(Deferred)** The two-plane design — an engine plane that authenticates *applications* (client keys), processes images, and durably retains only opt-in eval-set crops keyed to a pseudonymous **retention token**, isolated from a consumer plane holding the user identity — is the target architecture for when the product platformises (PRD §2.5). It is not what v1 ships.

---

## 2. Component responsibilities

### 2.1 Android client (Kotlin, native)
- Camera capture with framing guide, edge detection, glare warning; gallery/WhatsApp import.
- **Offline capture queue:** photo persisted locally, uploaded on reconnect, user notified when the check-list is ready.
- Verification UI (hard gate): editable medicine cards, ink-chip crops, empty-input anomaly flags, forced-confirm for non-daily frequencies, read-aloud.
- Local scheduling: meal-time capture, AC/PC offsets (−30 / +30 min), bedtime (HS) slot, course auto-stop, "ongoing" regimens.
- Notification delivery: `AlarmManager` exact alarms with `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` onboarding; **`WorkManager` windowed fallback + in-app warning** when exact alarms are denied or OEM battery optimization interferes.
- Local persistence: **Room (SQLite) as an offline cache** of the server-owned record — schedule, adherence log, offline capture queue, meal-time prefs. The server is the system of record; Room exists so notifications fire without connectivity.
- Native share sheet for ask-your-doctor and adherence-report (device-to-device; backend never involved).
- **Auth: phone + OTP, first (v0.4.3).** Sign-in is the app's first step; capture/verify/save all run inside the resulting session. **Persistent login:** a valid stored session skips sign-in and opens straight to the dashboard; logout clears it. Holds the session token in the Android keystore; no other account PII on device.

### 2.2 Extraction API (Spring Boot)
- **v1 (as built): `POST /extract`** (multipart) → synchronous `200` with the parsed, flag-annotated result. No job id, no polling — image → parse → return. No client-key or JWT check on this endpoint today (the app is the only caller; client-key auth is a pending backend task — see §4).
- **(Deferred) Target contract:** `POST /v1/extractions` (multipart) → `202 { extraction_id }`; `GET /v1/extractions/{id}` for polling; client-key authentication + per-key metering/rate-limit; versioned contract; sandbox environment. Returns once the engine plane is reintroduced.
- Enforces idempotency (deferred — no `Idempotency-Key` yet), request-size caps, MIME/type validation (implemented today).

### 2.3 Extraction pipeline (synchronous, v1)
Per PRD §7, run inline on the request thread — no worker, no queue:
```
preprocess (deskew, crop, enhance)
  → Vision LLM call — one call per scan, verbatim read, strict JSON schema + Indian Rx
    shorthand prompt (provider-pluggable: claude/gemini/grok/kimi/openai; default = Claude,
    model claude-sonnet-5 — see §2.5)
  → deterministic parser: frequency grammar (1-0-1, BD, TDS, QID, OD, HS, SOS, AC/PC, stat,
    weekly, EOD), strength anomaly FLAG (never auto-correct), per-field confidence scoring
    (non-daily ⇒ force confirm)
  → structured JSON result, returned directly to the caller
```
- **Nothing is persisted.** No job row, no result store, no source-image retention — the response is the only artifact. This is a structural simplification, not just a policy: there is no `extraction_job` table to write to (§5).
- **(Deferred)** letterhead-band stripping, fuzzy-match against a formulary, and per-field crop retention all belonged to the engine plane's job pipeline; none run in v1 (see §2.4).
- Calls the vision LLM under a **Data Processor agreement** (zero-retention / no-training terms — verify per provider, PRD open Q7).

### 2.4 Formulary service — disabled (deferred)
- **v1: disabled.** `FormularyMatcher.disabled()` — no fuzzy name match, no strength validation; the result schema's `formulary_id` field is simply unpopulated. The ~200K-SKU catalog and its loader are dropped with the engine plane (`formulary_sku` table removed, §5).
- **(Deferred) Target:** ~200K Indian brand SKUs, fuzzy name match + strength validation, converting LLM near-misses into exact `formulary_id` matches and flagging unknown strengths. Returns when the engine plane is reintroduced (§6.A has the full store evaluation, kept for that point).

### 2.5 Vision LLM boundary (external Data Processor, pluggable provider)
- **Provider is pluggable**, selected by `rxscan.vision.provider` behind a `VisionExtractionClient` interface: `claude` (`ClaudeVisionExtractionClient`) · `gemini` (`GeminiVisionExtractionClient`) · `grok`/`kimi`/`openai` (shared `OpenAiCompatibleVisionExtractionClient`, OpenAI chat/completions shape). **Current default: Claude, model `claude-sonnet-5`.** Model/provider choice can still move per the Week-0 bake-off (§10 PRD) or later cost/accuracy data — the interface is what makes that a config change, not a rewrite.
- Provider is a **Data Processor**, not a sub-controller.
- Contractual: zero retention, no training on our data, region/transfer terms verified (PRD open Q7).
- Treated as an unreliable dependency: timeouts, retries with backoff, circuit breaker, and a hard fallback to the honest-failure path ("couldn't read this — re-shoot").

### 2.6 Auth service (phone + OTP, sign-in first)
- `POST /v1/auth/otp/request` → send OTP to the phone via an SMS provider (a Data Processor); rate-limited per phone + per IP *(rate limiting deferred past slice A)*. Delivery is a provider strategy: stub `000000` default until the Gupshup contract closes; SMS only, no WhatsApp OTP.
- `POST /v1/auth/otp/verify` → on match, mint a session (JWT; **refresh tokens deferred past slice A** — a `401` routes the app back to signin); create the `users` row on first verify. The request still carries a `consents` field for contract compatibility, but since account-first (v0.4.3) moved sign-in *before* the consent screen, the app always sends it empty — consents now arrive afterward via `PUT /v1/me/consents` (process/retain_optin right after the consent screen, notify after the notif-perm screen).
- Identity = `user_id` (internal BIGINT identity, never client-visible) + `public_id` (UUID — the JWT `sub` and any client-facing id). The **phone number is the only PII**, stored **encrypted + as a keyed-HMAC blind index** so login can look up by phone without a plaintext column at rest.
- No name, email, or address is ever collected.

### 2.7 Prescription store (encrypted, system of record)
- Owns the confirmed prescription record (medicines, schedule, adherence) keyed to `userId`.
- **All prescription fields encrypted at rest** under per-user envelope encryption: a per-user data-encryption-key (DEK) wrapped by a KMS/HSM master key; the DEK is unwrapped in memory per request, never persisted in plaintext (§7.1).
- Serves authenticated CRUD; pushes changes to the device cache and ingests device-originated writes (new schedules, adherence events).
- Owns **delete/export by `userId`** — the DPDP data-principal rights path.
- **v1:** lives in the same single `rxscan` database as everything else — there is no separate engine/eval store to be isolated from in v1 (§1, §5). **(Deferred)** physical separation from an engine/eval store returns as a separability control (PRD §2.5) when that store exists again.

---

## 3. Key request flows

### 3.1 Sign in → scan → save → schedule (happy path, account-first)
```
App: on launch — stored session valid? → YES: skip straight to dashboard (persistent login)
                                        → NO:  phone-OTP sign-in FIRST
App: POST /v1/auth/otp/request {phone} → POST /v1/auth/otp/verify {phone, otp} → JWT
     (consents field sent empty — consent capture now happens post-login, see §2.6)
App: capture → POST /extract (multipart; no auth check on this endpoint yet — §2.2, §4)
API: preprocess → vision LLM call → deterministic parser → 200 {parsed result}, synchronously
     (no job id, no polling, no formulary match — nothing persisted; §2.3, §2.4)
App: render verify cards → USER CONFIRMS EACH (hard gate) → capture meal times
App: POST /v1/prescriptions (JWT, confirmed record) → server encrypts + stores under userId
App: compute per-dose fire times → schedule AlarmManager/WorkManager
      → write schedule + course to Room cache (for offline firing)
```
Sign-in now runs *before* capture, not at save — a stored session (persistent login) skips it entirely on relaunch; logging out clears the session. The extraction call is stateless and synchronous — no job id, no polling, no formulary match, nothing persisted. The confirmed record is persisted **encrypted, under `userId`**, and mirrored to the device cache. **(Deferred)** the two-plane version of this flow — extraction as an async, client-keyed job with polling and an optional retention token — returns with the engine plane; see the v0.2.3-era diagram this replaces.

### 3.2 Offline capture & offline save
Photo → Room queue (`capture_queue`) → connectivity regained → upload → normal extraction flow → local notification "your check-list is ready." If the user confirms a schedule while offline, reminders schedule from the device cache immediately and the encrypted record is queued and pushed to `/v1/prescriptions` on reconnect. Queues survive app kill; retried with backoff.

### 3.3 Sync (server = system of record)
```
On app open / token refresh:
  App: GET /v1/prescriptions?since=cursor (JWT) → server returns changes → update Room cache
On device-originated change (adherence event, edit):
  App: enqueue → POST /v1/prescriptions/... (JWT) → server upserts (authoritative)
```
Server-authoritative, so conflict handling is simple: server state wins on read; device writes are appends (adherence) or explicit edits. The device cache is disposable — a new phone rehydrates from the server after phone-OTP login.

### 3.4 Delete / export (DPDP data-principal rights)
```
Account/record: DELETE /v1/me (JWT) → hard-delete the encrypted prescription record,
                the user row, the phone ciphertext + blind index; then wipe Room cache.
Export:         GET /v1/me/export (JWT) → decrypted portable bundle (schedule + adherence).
```
Both are post-slice-A, DPDP launch-blockers (§4). **(Deferred)** a second erasure path — `DELETE /v1/retention/{token}` / `GET /v1/retention/{token}/export`, hard-deleting opt-in eval-set crops/corrections by pseudonymous token — existed in the v0.4 two-plane design and returns with the engine/eval store; there is no retained eval data in v1 to erase (§5, §6.D, §6.H are kept as the design for when it returns).

### 3.5 Ask-your-doctor / adherence-report share
OS share sheet only. Crop + pre-written question (or adherence summary) handed to WhatsApp/SMS/email. **Never touches the backend** — keeps this feature entirely outside the DPDP processing surface.

---

## 4. API contract (v1, expanded)

v1 has one auth domain: phone-OTP → JWT, and it runs *before* the extraction/verify/save
sequence (account-first, PRD §6 step 1), not after. The extraction endpoint itself carries no
auth check yet (see below).

*Extraction (v1, as built — no auth check on this endpoint yet):*

| Method | Path | Purpose | Notes |
|---|---|---|---|
| `POST` | `/extract` | Submit image | multipart; synchronous `200` with the parsed result — no job id, no polling, no `Idempotency-Key`, no client key. The app is the only caller today (client-key auth is a pending backend task). |

**(Deferred) Target engine-plane contract** — returns when the product platformises (client-key auth, `X-Client-Key` = the *application*, no user):

| Method | Path | Purpose | Notes |
|---|---|---|---|
| `POST` | `/v1/extractions` | Submit image | multipart; `Idempotency-Key` header; optional `X-Retention-Token`; returns `202 {extraction_id}` |
| `GET` | `/v1/extractions/{id}` | Poll result | returns `status ∈ {queued, processing, complete, failed}` |
| `DELETE` | `/v1/retention/{token}` | Purge retained eval data | hard-delete all crops/corrections for the pseudonymous token |
| `GET` | `/v1/retention/{token}/export` | Export retained eval data | machine-readable bundle |
| `POST` | `/v1/corrections` | Submit user corrections | opt-in only; feeds eval set; carries retention token + provenance |
| `GET` | `/v1/formulary/search` | (partner) drug lookup | optional B2B convenience endpoint |

*Authenticated endpoints (phone-OTP → JWT, `userId` — the only place a user identity exists):*

| Method | Path | Purpose | Notes |
|---|---|---|---|
| `POST` | `/v1/auth/otp/request` | Send OTP | `{phone}`; rate-limited per phone + IP; SMS via Data Processor |
| `POST` | `/v1/auth/otp/verify` | Verify + session | `{phone, otp, consents:[{purpose, granted, granted_at}]}` → JWT; creates `users` row on first verify. **v0.4.3:** the app always sends `consents: []` here — sign-in now runs before the consent screen, so consent capture moved entirely to `PUT /v1/me/consents` below. |
| `PUT` | `/v1/me/consents` | Upload consents post-login | JWT; append-only rows (e.g. `notify` from the notif-perm screen) |
| `PUT` | `/v1/me/preferences` | Upsert preferences blob | JWT; FE-owned opaque JSON (meal times, toggles); encrypted; one row per user |
| `GET` | `/v1/me/preferences` | Fetch preferences | JWT; device rehydrate |
| `POST` | `/v1/prescriptions` | Save confirmed record | JWT; server encrypts + stores under `userId` |
| `GET` | `/v1/prescriptions?since=` | Sync pull | JWT; `since` optional — absent ⇒ full pull for the device cache |
| `PATCH` | `/v1/prescriptions/{id}` | Edit / adherence event | JWT; server is authoritative |
| `DELETE` | `/v1/me` | Delete account + record | JWT; hard-delete encrypted record + user + phone ciphertext/blind-index *(post-slice-A)* |
| `GET` | `/v1/me/export` | Export account data | JWT; decrypted portable bundle *(post-slice-A)* |

Slice A (`docs/superpowers/specs/2026-07-23-consumer-api-v1-design.md`) implements everything
above except `DELETE /v1/me` and `GET /v1/me/export` — DPDP launch-blockers, not integration
blockers. Prescription + preferences payloads are **FE-owned and server-opaque** (encrypted
blobs); the JWT `sub` is `users.public_id`, never the sequential `user_id`.

**Result schema** (per PRD §7, unchanged shape):
```json
{
  "status": "complete",
  "medications": [{
    "drug":        { "value": "Pantocid 40mg", "formulary_id": null, "confidence": 0.93,
                     "image_region": { "x": 0, "y": 0, "w": 0, "h": 0 } },
    "frequency":   { "raw": "1-0-1", "slots": ["morning","night"], "pattern": "daily", "confidence": 0.99 },
    "meal_timing": { "value": "before_food", "confidence": 0.97 },
    "duration":    { "type": "days", "value": 5, "confidence": 0.88 }
  }],
  "flags":    ["strength for item 2 not found in formulary — user re-check"],
  "warnings": ["duration missing for item 3"]
}
```
- `duration.type ∈ days | ongoing | unspecified`
- `frequency.pattern ∈ daily | weekly | alternate_day | prn` — any non-`daily` ⇒ forced confirm on client.
- **CDSCO invariant:** a `flag` names the field and asks for re-check. It carries **no `suggested_value`**. There is no field in this schema through which the backend can propose a corrected dose. This is enforced by schema, not convention.
- **v1: `formulary_id` is always `null`.** Formulary matching is disabled (§2.4); the field stays in the schema so re-enabling it is additive, not a breaking change.

**Cross-cutting API rules**
- **v1, as built:** `/extract` is synchronous, unauthenticated, and has no idempotency key — a retried upload just re-runs the vision call. Request-size/MIME validation is enforced (`413`/`415`); `422` unprocessable image and `503` LLM-unavailable/rate-limited are implemented (§2.3, `VisionUnavailableException`/`VisionRateLimitedException`).
- **(Deferred) Target rules**, once the engine plane returns: **Idempotency** — `POST /v1/extractions` keyed by `Idempotency-Key`, a retried upload returns the original `extraction_id`, not a duplicate job; **Metering** — every extraction increments a per-key counter, rate limits + monthly caps enforced at the gateway; `429` metered-out.
- **Versioning:** path-versioned (`/v1/…`) for the authenticated endpoints; result schema is additive-only within a major version.

---

## 5. Logical data model

**v1 (as built): one logical database, `rxscan`.** It holds only the five consumer-plane tables
below (`users`, `user_consent`, `user_preference`, `prescription`, `adherence_event`) — encrypted
where the field is sensitive, keyed to `userId`. There is no separate engine store in v1: the
`extraction_job`, `formulary_sku`, `correction`, `retained_item`, `client_key`, `usage_meter`, and
`consent_provenance` tables described below are **dropped**, not just unused — they don't exist in
`V1__init.sql`. Extraction persists nothing (§2.3), so there's no job/result table to have; no
formulary catalog is loaded (§2.4); there's no eval-set retention pathway, so no crops/corrections/
retention-tokens/provenance log exist to store.

**(Deferred) Target: two logical databases.** The **engine store** (below, first group) would
carry no user identity; the **consumer store** (second group) holds the encrypted, `userId`-keyed
record and would be physically separate. This is the schema that returns when the product
platformises (PRD §2.5) — kept here, unchanged, as the design to build from at that point.

### (Deferred) Engine store (no user identity) — not present in v1

**`extraction_job`** — transient, short TTL
`extraction_id (pk)` · `client_key_id` · `status` · `idempotency_key` · `created_at` · `result (json)` · `retention_token?` · `expires_at`
→ No user identity. Auto-expires; `result` purged on TTL unless promoted to retention.

**`retained_item`** — opt-in only, the eval-set moat
`item_id (pk)` · `retention_token (indexed)` · `field_type` · `crop_ref (object-store key)` · `extracted_value` · `corrected_value?` · `confidence` · `created_at`
→ Primary access: **by `retention_token`** (delete/export). Crops are blobs in object storage; only the reference lives here.

**`correction`** — eval-set labels
`correction_id (pk)` · `retention_token` · `extraction_id` · `field_type` · `model_value` · `user_value` · `created_at`

**`formulary_sku`** — reference catalog (~200K)
`formulary_id (pk)` · `brand_name` · `generic` · `strength` · `form` · `search_terms` · trigram/inverted index

**`client_key`** — application identity
`client_key_id (pk)` · `owner` (self / partner) · `hashed_key` · `tier` · `rate_limit` · `monthly_cap` · `status`

**`usage_meter`** — per-key metering
`client_key_id` · `window` · `count` (high-write, time-bucketed)

**`consent_provenance`** — append-only diligence log
`event_id (pk)` · `retention_token` · `purpose ∈ {process, retain, backup}` · `granted` · `source` · `created_at` · `immutable`
→ **Insert-only.** No updates, no deletes except a token-scoped erasure that is itself a logged event. This is the DPDP + acquisition-diligence artifact (PRD §2.5, §3.2).

### Consumer store — v1's entire database (encrypted, `userId`-keyed)

**`users`** — the only PII, minimised
`user_id (pk, BIGINT identity — internal-only, never client-visible)` · `public_id (UUID, unique — JWT sub / anything client-facing)` · `phone_enc` (ciphertext) · `phone_blind_idx` (keyed-HMAC, unique, indexed — login lookup) · `dek_wrapped` (per-user data key, wrapped by KMS master key) · `is_minor_verified`
→ No name/email/address. Login looks up by `phone_blind_idx`; the plaintext phone exists only transiently in the OTP request.

**`user_consent`** — append-only consent log
`consent_id (pk)` · `user_id (fk, indexed)` · `purpose ∈ {process, notify, retain_optin}` · `granted` · `granted_at` (device-side grant time)
→ Withdrawal = new row with `granted=false`, never an UPDATE. **v0.4.3:** since sign-in now runs before the consent screen, consents no longer travel with `otp/verify` (that field is sent empty) — they're uploaded via `PUT /v1/me/consents` post-login instead (`process`/`retain_optin` right after the consent screen, `notify` after notif-perm). `created_at` is the server receipt.

**`user_preference`** — encrypted FE-owned blob, exactly one row per user
`user_id (pk, fk)` · `payload_enc` (meal times, toggles — server never parses it)
→ Upserted after login and on every change; pulled on device rehydrate.

**`prescription`** — the confirmed record, encrypted
`rx_id (pk)` · `user_id (indexed)` · `payload_enc` (envelope-encrypted JSON: medicines, slots, meal timing, duration, course dates)
→ Every medical field lives inside `payload_enc`; nothing sensitive in cleartext columns. Decrypted in memory per request via the user's DEK.

**`adherence_event`** — encrypted history
`event_id (pk)` · `user_id (indexed)` · `rx_id` · `payload_enc` (slot time, state ∈ {taken, skipped, snoozed})

Every table additionally carries `created_at` + `updated_at`, maintained by a
`BEFORE UPDATE` trigger (`set_updated_at()`) — Postgres has no `ON UPDATE CURRENT_TIMESTAMP`.
(The (deferred) engine store above would carry the same convention if/when it exists again.)

→ Access is **by `user_id`** (read/sync/delete/export). Device Room mirrors these as a disposable offline cache (`schedule`, `dose`, `adherence_event`, `meal_prefs`, `capture_queue`).

Three schema-level invariants worth restating because they're compliance firewalls, not preferences:
1. **v1:** the single `rxscan` database is the *only* place a `userId` exists — there's no engine store to keep it out of, because there's no engine store. **(Deferred)** when the engine store returns, its rows must go back to being keyed to token or client, never to a person, so the platform asset stays separable (PRD §2.5).
2. Every sensitive field in the (single, v1) store is inside an **encrypted payload** — no medical data or plaintext phone in queryable columns.
3. No table anywhere stores a system-proposed corrected value for a flagged field.

---

## 6. Database strategy & evaluation

This is the core of the doc. The PRD names PostgreSQL + S3 + "async worker queue" but leaves the store choices open. Below I evaluate candidates **per workload**, because RxScan has seven data workloads with genuinely different shapes, then give a consolidated recommendation.

The governing principle: the engine plane stays small and mostly transient (transient jobs + an opt-in append-mostly eval set, no user identity), so it wants *one boring transactional store*. The consumer plane adds one deliberately-separate, **encrypted** store for the user's `userId`-keyed record. Specialized stores get added only where a workload's shape actually breaks Postgres — I show where each wins so the "add it later" triggers are explicit.

> **v1 status of this section:** workloads A–F below (formulary, extraction jobs, job queue,
> retained crops, metering, provenance log) all belong to the **engine plane, which is deferred**
> (§1, §5) — none of them are built or needed in v1. Only **H** (user account + encrypted
> prescription store) and **G** (on-device Room cache) exist today, and H is simply *the* database
> now, not one of two. The analysis below is kept in full because it's the design to build from
> when the product platformises — read it as "if/when the engine plane returns," not as v1's
> current store layout. §6.9 restates this with a v1-as-built summary at the top.

### 6.0 The workloads

| # | Workload | Shape | Volume (v1) | Hot path? |
|---|---|---|---|---|
| A | Formulary lookup | read-heavy, fuzzy string match | ~200K SKUs, static | yes (in extraction) |
| B | Extraction jobs + results | transactional, short-lived, poll-by-id | low | yes |
| C | Async job queue | FIFO-ish, at-least-once, retry/DLQ | low | yes |
| D | Retained crops + corrections (eval set) | append-mostly, delete/export by token, blobs | grows to 2K+ then more | no |
| E | Metering / rate-limit | high-write counters, time-windowed | per-request | yes |
| F | Consent + provenance log | append-only, immutable, audit | low | no |
| G | On-device store | local relational, offline cache | per-device | yes (device) |
| H | User account + encrypted prescription store | per-user relational, encrypted at rest, delete/export by userId | one row-set per user | yes (save/sync) |

---

### 6.A Formulary lookup (fuzzy match, ~200K SKUs)

The single biggest accuracy lever (PRD §7). LLM output ("Pantocid 40" ) must snap to a real SKU despite spelling drift.

| Option | Pros | Cons |
|---|---|---|
| **PostgreSQL + `pg_trgm` (GIN)** ✅ | One store to run; trigram similarity handles brand-name fuzz well; strength/form as normal columns for exact validation; 200K rows is trivial in RAM; no extra infra | Trigram is lexical only — won't catch phonetic misspellings ("Augmentin"→"Ogmentin") as well as a dedicated engine; ranking tuning is manual |
| **Elasticsearch / OpenSearch** | Best-in-class fuzzy (edit distance, phonetic/`metaphone`, custom analyzers); relevance scoring out of the box | A whole cluster to operate, back up, secure (health-adjacent data) for 200K static rows; overkill at v1 scale; adds a second consistency domain |
| **Redis + RediSearch** | In-memory, sub-ms; good fuzzy support | Another store; persistence/ops overhead; catalog fits in Postgres cache anyway so latency gain is marginal here |
| **pgvector (semantic embeddings)** | Catches semantic/phonetic near-misses a trigram misses; stays inside Postgres | Embedding the query adds latency + a model dependency on the hot path; likely unnecessary given the LLM already did the hard reading |

**Recommendation:** **Postgres `pg_trgm`** for v1. It's inside the store you already run, and 200K static rows is a non-problem. **Trigger to revisit:** if bake-off/beta data shows formulary miss-rate is a top-2 error source, add a **phonetic layer** — cheapest first as a `pg_trgm` + `metaphone`/`dmetaphone` combination (still in Postgres via `fuzzystrmatch`), and only escalate to OpenSearch if that plateaus. Consider **pgvector** only if phonetic still misses semantic cases, and even then keep it in Postgres.

---

### 6.B Extraction jobs + structured results

Write a job, poll by id, store a nested-JSON result, expire it. Classic small-transactional + document-ish payload.

| Option | Pros | Cons |
|---|---|---|
| **PostgreSQL (`jsonb` result)** ✅ | `jsonb` stores the medications array natively and queryably; TTL/expiry via a cleanup job; transactional with metering + provenance in one store; you already run it | Not a document DB — but the query patterns here (get-by-id) don't need one |
| **MongoDB** | The nested `medications` payload is a natural document; flexible schema as the result shape evolves | A second store + second consistency/ops domain for a payload Postgres `jsonb` already handles; you lose single-transaction coupling with metering/provenance |
| **DynamoDB / KV** | Trivial get-by-id, auto-scale, TTL built in | Managed-cloud lock-in; poor fit for the analytical queries the eval set later needs; another API surface; overkill at v1 volume |
| **Redis (as primary)** | Fast, TTL native | Not a system of record for anything you must not lose mid-extraction; durability story is wrong for a job you charge for |

**Recommendation:** **Postgres with a `jsonb` result column** and a scheduled TTL sweep. The result shape is nested but the *access* pattern is get-by-id, which is Postgres's easiest case. Keeping jobs, metering, and provenance in one transactional store is worth more than the document ergonomics Mongo would add. **Trigger to revisit:** only if extraction volume grows enough that job churn contends with the eval-set/analytical load — at which point split reads to a replica before splitting stores.

---

### 6.C Async job queue

Extraction is async with retry + dead-letter. Options range from "a table" to "a broker."

| Option | Pros | Cons |
|---|---|---|
| **Postgres queue (`FOR UPDATE SKIP LOCKED`)** ✅ | Zero new infra; transactional with the job row (enqueue + status in one commit → no dual-write); trivially observable via SQL; DLQ is just a status; more than fast enough at v1 volume | Not built for very high throughput; long-poll workers add DB load; you outgrow it eventually |
| **Redis (Streams / list)** | Fast, simple, consumer-groups for at-least-once; light ops | Dual-write risk (job in Postgres, message in Redis) needs care; persistence tuning; another store |
| **Kafka** | Durable, replayable, partitioned, battle-tested (and you already run it at Flyra); great if extraction fans out into multiple downstream consumers later | Heavy for one producer + a worker pool; operational weight (brokers, KRaft, offsets) unjustified at v1; replay/log-compaction features go unused here |
| **SQS / managed queue** | No ops, DLQ built-in, at-least-once | Cloud lock-in; still a dual-write with the Postgres job row; another failure domain |

**Recommendation:** **Postgres `SKIP LOCKED` queue** for v1 — the enqueue and the job-status write happen in the *same transaction*, which kills the dual-write consistency problem outright and needs no new infra. This is the highest-leverage "one fewer store" decision in the doc. **Trigger to revisit:** move to **Kafka** (which you already operate) when either (a) throughput makes DB-polling a bottleneck, or (b) extraction results need to fan out to multiple independent consumers (e.g., a real-time eval-metrics stream + a partner webhook + the store). Until one of those is true, a broker is cost without benefit.

---

### 6.D Retained crops + corrections (the eval set / moat)

Append-mostly, must support **delete-by-token** and **export-by-token** (DPDP), holds blob crops, grows to 2K+ labeled prescriptions then indefinitely, and is later consumed for ML/analytics. This is the asset the platform thesis (PRD §2.5) is built on, so it's worth designing deliberately.

| Option | Pros | Cons |
|---|---|---|
| **Postgres (metadata) + object store (crop blobs)** ✅ | Blobs belong in object storage, not a DB; per-field crop + label + confidence as rows; `retention_token` index makes delete/export O(rows-for-token); server-side encryption + lifecycle rules on the bucket; joins to corrections/provenance for diligence exports | Analytical/ML queries at large scale eventually want columnar; managing blob-lifecycle ↔ row-lifecycle consistency needs a deletion routine that hits both |
| **MongoDB (+ GridFS)** | Document-natural; flexible label schema | Second store; GridFS is a weak blob story vs object storage; delete-by-token fine but you lose the transactional link to provenance |
| **Data lake (Parquet on object store)** | Ideal for the *ML/eval* read pattern; cheap at scale; columnar | Terrible for point delete-by-token (DPDP erasure), which is a hard v1 requirement; wrong as the *system of record* — right as a *derived* export later |
| **Dedicated vector DB** | If corrections drive embedding-based retrieval later | No v1 need; premature |

**Recommendation:** **Postgres metadata + object-store crops.** Crops as objects (encrypted, lifecycle-managed), everything queryable/erasable as Postgres rows keyed by `retention_token`. Delete/export is then a token-scoped transaction that (1) deletes rows and (2) issues object deletes — implement as a single idempotent routine so a partial failure is retry-safe. **Trigger to revisit:** when the eval set is large enough that ML/eval reads strain the OLTP store, periodically **export a de-identified snapshot to Parquet** for training/benchmarking — a *derived, append-only* copy, never the erasable system of record. This keeps DPDP erasure correct (delete hits the source of truth) while giving ML the columnar read path it wants.

**Non-negotiable here:** crops must be de-lettered (header band stripped) *before* storage, encrypted at rest, and the deletion routine must be provably complete for a token, because "delete all my data" is a legal right, not a UX nicety.

---

### 6.E Metering / rate-limiting

Per-request counter increments, time-windowed, read on the hot path to enforce caps.

| Option | Pros | Cons |
|---|---|---|
| **Redis (INCR + TTL, sliding window)** ✅ *(when needed)* | Purpose-built for counters; atomic increments; sliding/fixed-window trivial; no DB write-contention on the hot path | A store to run; counters are volatile (acceptable — bill from an async ledger, not the live counter) |
| **Postgres counter rows** ✅ *(v1 default)* | No new infra; durable; fine at low request volume; transactional with the extraction job | Row-level contention under high write concurrency; not what Postgres is best at |
| **API-gateway native metering** | If the chosen gateway does it, zero app code | Ties metering policy to gateway; less flexible for partner-specific caps/billing |
| **Time-series DB** | Great for usage *analytics* | Overkill for *enforcement*; wrong tool for a live cap check |

**Recommendation:** **Postgres counters for v1** (volume is low; one fewer store), with the **explicit intention to move enforcement to Redis** the moment request concurrency makes counter rows contend — this is the *first* place Redis earns its keep in this system. Keep the durable billing record separate from the live enforcement counter regardless of store, so a volatile counter is never the source of truth for what a partner owes.

---

### 6.F Consent + provenance log

Append-only, immutable, audit-grade, legal weight (DPDP + acquisition diligence).

| Option | Pros | Cons |
|---|---|---|
| **Postgres append-only table** ✅ | Insert-only enforced via permissions/triggers (revoke UPDATE/DELETE from the app role); transactional with the consent event that caused it; queryable for diligence exports; simple | "Immutability" is policy-enforced, not cryptographic — a DB admin could tamper. Mitigate with WORM backups + periodic hash-chaining if diligence demands it |
| **Ledger DB (e.g., QLDB-style)** | Cryptographically verifiable immutability out of the box | Managed lock-in; another store; heavier than v1 needs; loses transactional coupling with the event |
| **Append-only object storage (WORM)** | True write-once at the storage layer; cheap, durable | Not queryable; wrong as the primary log — right as a *tamper-evident backup* of the Postgres log |

**Recommendation:** **Postgres append-only table**, app role stripped of UPDATE/DELETE, exported periodically to **WORM object storage** as the tamper-evident backup. This gives diligence-grade provenance without standing up a ledger DB. **Trigger to revisit:** if an acquirer's diligence explicitly demands cryptographic non-repudiation, add hash-chaining to the log rows (each row hashes the prior) before reaching for a dedicated ledger product.

---

### 6.G On-device store (Android)

The PRD already specifies Room. Recording the evaluation for completeness since this store holds the *actual* schedule and adherence history — the data that matters most to the user.

| Option | Pros | Cons |
|---|---|---|
| **Room (SQLite)** ✅ | First-party, mature; SQL power for the merged-schedule + adherence queries (§9 dedupe, denominators); migrations; testable; works fully offline | Boilerplate vs newer options (acceptable) |
| **Raw SQLite** | Max control | Reinvents what Room gives; error-prone migrations |
| **DataStore** | Great for the small meal-time/settings prefs | Not relational — wrong for schedules/adherence; use it *alongside* Room for prefs only |
| **Realm / ObjectBox** | Ergonomic object persistence, fast | Third-party dependency + lock-in; the relational queries here favor SQL |

**Recommendation:** **Room** for schedule/dose/adherence/capture-queue, **DataStore** for simple prefs (meal times, notification-name toggle). Standard, offline-correct, and the adherence-denominator logic (PRD §9) is cleaner in SQL. In v0.4 Room is a **disposable cache** of the server record, not the system of record — a new device rehydrates from the server after phone-OTP login.

---

### 6.H User account + encrypted prescription store

The consumer plane: phone-OTP identity + the user's confirmed prescription record, **encrypted at rest, keyed to `userId`**, with the phone number as the only PII. This is the one place identity-linked health data lives, so encryption and store-isolation dominate the choice.

Two design axes matter more than the base engine: **(a) the encryption model** and **(b) the store**.

**(a) Encryption model**

| Option | Pros | Cons |
|---|---|---|
| **Envelope encryption, server-decryptable (KMS/HSM)** ✅ | Per-user DEK wrapped by a KMS master key; standard, fast, supports multi-device + account recovery cleanly under phone-OTP; satisfies DPDP's encryption mandate; server can render/report on data for the user | The operator *can* decrypt (mitigate: HSM-held master key, tight IAM, audit logs, no standing human access); the stored record is a server-side asset, softening §2.5 separability |
| **End-to-end / zero-knowledge (client-held key)** | Server stores only ciphertext it cannot read — strongest privacy, best preserves §2.5 (an acquirer/attacker gets useless ciphertext) | Phone-OTP yields no stable client secret, so needs a **key-recovery design** (encrypted key backup under a user passphrase, or escrow); lose-device UX is hard; server-side features (web view, caregiver, reports) become constrained |

**Baseline: envelope encryption**, because it works smoothly with phone-OTP and multi-device and still meets "completely encrypted at rest." E2E is a scoped upgrade if zero-knowledge/separability is weighted above recovery simplicity — flagged in §9 open questions and PRD Q12.

**Phone minimisation:** store `phone_enc` (ciphertext) + `phone_blind_idx` (keyed-HMAC) so login looks up by phone without a plaintext column; the plaintext exists only transiently during OTP request.

**(b) Store**

| Option | Pros | Cons |
|---|---|---|
| **PostgreSQL — separate DB, app-layer envelope encryption** ✅ | Encrypt in the app layer (fields inside `payload_enc`) with the per-user DEK — DB never sees plaintext; RLS as defence-in-depth per `userId`; one technology already operated; separate logical DB preserves §2.5 separability + clean carve-out/erasure; natural for v2 caregiver relational graph | You implement the envelope/DEK plumbing (well-trodden with a KMS SDK); encrypted columns aren't richly queryable — fine, since access is by `userId` |
| **Postgres `pgcrypto` (in-DB encryption)** | Simple; encryption in SQL | Keys pass through the DB / connection — weaker separation than app-layer envelope; harder KMS integration |
| **DynamoDB + KMS** | Managed, per-`userId` partition scales, encryption integrated | Cloud lock-in; v2 caregiver relational queries awkward; another failure domain to secure + prove erasure on |
| **MongoDB (Client-Side Field-Level Encryption)** | CSFLE is purpose-built field encryption; document-per-user natural | Second store + CSFLE key-vault ops; caregiver graph less natural than SQL |
| **Firestore / managed sync** | Offline sync + auth built in | Puts health records in a third-party cloud → cross-border/Data-Processor DPDP question; lock-in; weakens separability |

**Recommendation:** **PostgreSQL in a separate logical database, with application-layer envelope encryption** (per-user DEK wrapped by KMS/HSM), RLS as defence-in-depth, phone stored as ciphertext + blind index. Same technology you already run, encryption owned in the app so the DB never holds plaintext, and the store stays physically separate from the engine/eval data so the platform asset remains transferable. **Trigger to revisit:** if v2 caregiver real-time fan-out (live missed-dose alerts to family) proves painful on request/response, add a push channel for the notification path — without moving the system of record off Postgres. If zero-knowledge is chosen, the *store* recommendation is unchanged; only the key-custody + recovery design changes.

**Non-negotiables:** KMS/HSM-held master key with no standing human decrypt access + audit logging; `userId` is pseudonymous (UUID), phone is the only PII and is encrypted + blind-indexed; **delete/export by `userId`** tested to completeness (rows + wrapped DEK destroyed → record cryptographically unrecoverable); minor gating per §3.2; consumer DB physically separate from engine/eval stores.

---

### 6.9 Consolidated recommendation

**v1, as actually built:**

| Store | Serves | Why |
|---|---|---|
| **PostgreSQL — single `rxscan` DB** | H only (`users`, `user_consent`, `user_preference`, `prescription`, `adherence_event`) | No engine-plane workloads (A–F) exist in v1 — see the callout at the top of §6 — so there is nothing to keep in a separate database. One database, app-layer envelope encryption for sensitive fields |
| **KMS / HSM** | envelope-encryption master key for H | Master key never leaves the HSM; per-user DEKs wrapped/unwrapped through it; no standing human decrypt access |
| **Room + DataStore (device)** | G | Disposable offline cache of the server record, so reminders fire without connectivity |

No object storage is provisioned in v1 either — extraction doesn't persist the source image (§2.3), so there's nothing transient to hold. **(Deferred) Target — two Postgres DBs**, once the engine plane returns:

| Store | Serves | Why |
|---|---|---|
| **PostgreSQL — engine DB** | B (jobs+results, `jsonb`), C (queue, `SKIP LOCKED`), A (formulary, `pg_trgm`), D-metadata, E (counters), F (provenance) | One transactional store removes every dual-write/consistency seam; low-volume, no user identity; you know it deeply |
| **PostgreSQL — consumer DB (separate, encrypted)** | H (phone-OTP account + prescription record) | Separate logical DB with app-layer envelope encryption + RLS; isolates identity-linked health data and preserves §2.5 separability. Same technology, deliberately separate database |
| **Object storage (S3-compatible)** | transient source images (lifecycle auto-delete), D crop blobs (encrypted) | Blobs never belong in a DB; lifecycle rules enforce data-minimisation automatically |

**Explicitly *not* in v1 (nor in the deferred target until it platformises), with the trigger that adds each:**

| Deferred store | Add when | For which workload |
|---|---|---|
| **Redis** | request concurrency makes Postgres counter rows contend | E (metering) first, then C if queue-polling load grows |
| **Kafka** | extraction must fan out to multiple consumers, or throughput breaks DB-polling *(you already run it at Flyra)* | C (queue) |
| **OpenSearch / pgvector** | formulary miss-rate proven a top-2 error source *after* trying phonetic-in-Postgres | A (formulary) |
| **Parquet / lake** | eval-set ML reads strain OLTP | D (derived, de-identified snapshot — never the erasable SoR) |
| **Ledger DB** | acquirer diligence demands cryptographic non-repudiation | F (before that: hash-chain the Postgres log) |

**The through-line:** v1 ships the smallest thing that's honest — one Postgres database, one encrypted `userId`-keyed record, no engine plane at all, because there's no partner/eval/metering surface to isolate yet. **(Deferred)** when the product platformises, the engine plane comes back tiny, transient, and user-anonymous, with the consumer plane split back out as its own deliberately-separate, encrypted Postgres DB — and polyglot persistence beyond *that* remains a scaling response, not a starting posture: every store you add is a new consistency domain, backup target, and (for health data) a new attack surface to secure and prove you can erase for DPDP. Let measured pain, not anticipation, pull in Redis → Kafka → search → lake in that order, on top of the two-DB split, once it returns. The consumer/engine split is the one up-front separation worth re-introducing at that point — not for scale, but to keep identity-linked health data contained and the platform asset cleanly transferable.

---

## 7. Cross-cutting concerns

### 7.1 Security & DPDP mechanics
- **v1: one identity domain.** There's a single database and a single identity (`userId`); every authenticated endpoint (`/v1/me/**`, `/v1/prescriptions/**`) is scoped to it via `JwtInterceptor`. The `/extract` endpoint carries no identity check today (§2.2, §4). **(Deferred)** the "two planes, two identities" isolation — an engine plane holding only a client key + optional pseudonymous retention token, never learning who the user is — returns when the engine plane does.
- **Encryption of the consumer store (the load-bearing control).** All prescription/adherence data lives inside an **app-layer envelope-encrypted payload**: a per-user DEK encrypts the fields, and the DEK is wrapped by a **KMS/HSM master key**. The DB stores only ciphertext + wrapped DEKs; plaintext exists only in service memory during a request. The master key never leaves the HSM; there is **no standing human decrypt access**, and every unwrap is audit-logged. Unchanged by the single-DB move — this is the one database now, not the consumer half of two.
- **Phone minimisation.** Phone is the sole PII: stored as `phone_enc` + a keyed-HMAC `phone_blind_idx` for login lookup — no plaintext phone column. No name/email/address is ever collected.
- **Cryptographic erasure.** `DELETE /v1/me` destroys the user's rows *and* their wrapped DEK — with the DEK gone, any residual ciphertext (backups, WAL) is unrecoverable. Integration-tested to assert zero recoverable data for a `userId`. **(Deferred)** eval-set crops erasing independently by token — there's no eval-set store in v1 to erase.
- **Defence in depth:** Postgres RLS scopes every query to one `userId`; TLS in transit; encryption at rest on the database. **(Deferred)** "both Postgres DBs" and the crop-bucket signed-URL control return with the engine plane and its object storage — v1 has neither a second database nor an object store to secure.
- **Data minimisation as infra:** the source image is never persisted at all in v1 — extraction is stateless (§2.3), so there's no lifecycle rule to write because there's no file to expire. We store the *structured* record, never the image. **(Deferred)** the target design (transient object storage with a minutes-after-extraction lifecycle rule) applies once extraction becomes an async, job-backed engine-plane call again.
- **Minors:** server persistence of a child's record is gated behind guardian verification; the local scan→verify path is unaffected (on-device before save).
- **Access control + audit logging** on the consumer store (DPDP mandate). **(Deferred)** retained eval data + provenance logging return with the engine plane.

### 7.2 Reliability
- **v1:** extraction is a single synchronous request/response — there's no job to retry or dead-letter. `VisionUnavailableException`/`VisionRateLimitedException`/`VisionUpstreamException` map to `503`/`503`/`502` so a failed vision call surfaces as an honest, retryable error to the app, never a silent wrong guess.
- **(Deferred)** once extraction is async again: **idempotent uploads** (`Idempotency-Key`) so retries don't double-process; **worker retries** with exponential backoff; **dead-letter** status after N attempts.
- **LLM circuit breaker + timeout** — still the goal for the synchronous call too; on failure, return `503` retryable and let the app retry.
- **Exact-alarm degradation is a first-class UX path**, not an error: WorkManager fallback + explicit in-app warning when Android 14+ restrictions or OEM battery optimization bite.

### 7.3 Observability
- Structured logs (Sumo Logic-friendly, given your stack) with **no PII and no image content** — log field types, confidences, flag counts, timings; never the extracted drug values tied to anything identifying. **(Deferred)** there's no `extraction_id` to key logs by in v1 — the call has no persisted job record (§2.3).
- **The safety metric (PRD §9)** — confirmed-schedule audit error rate — needs the eval pipeline to sample opt-in confirmed schedules against source crops. **(Deferred)** there's no eval-set/retained-crop pipeline in v1 to sample from; this metric can't be instrumented until the engine plane (and retained crops) return. Any undetected wrong field is still a P0 once it can be.
- Track p50/p95 extraction latency (target p50 < 8s), LLM error rate. **(Deferred)** queue depth, DLQ rate, and per-key metering return with the async job queue and client-key layer.

### 7.4 Cost
- Vision-LLM cost per scan is the dominant unit cost and sets both the free-tier cap and B2B pricing. PRD §7 sets the planning figure at **≈ ₹1/scan** via two-stage routing (Flash-class first, escalate low-confidence to Sonnet/Pro-class); meter the real number precisely from day one (PRD Q2 resolved, Q8 tunes the cap). **Current provider:** Claude (`claude-sonnet-5`) is the configured default (§2.5) — the pluggable `VisionExtractionClient` interface means swapping provider/model for the bake-off's answer, or for cost reasons, is a config change (`rxscan.vision.provider`/`.model`), not a rewrite.
- **(Deferred)** per-key/per-partner metering and the resulting billing ledger (§6.E) return with the engine plane; v1 has no client-key layer to meter against.
- No object-storage cost in v1 — there's no source-image or crop persistence to store or expire (§2.3, §7.1).

---

## 8. Compliance → concrete engineering constraints

A table mapping the two regulatory firewalls onto things a developer must actually build, so nothing gets lost in translation:

| Constraint (PRD) | Concrete engineering rule |
|---|---|
| Flag, don't correct (CDSCO) | Result schema has **no `suggested_value`** field; flag UI binds to an empty input; no code path writes a proposed dose |
| Non-advisory framing | No stored/derived "indication" or pseudo-generic; PRN shown in the paper's own words only |
| Purpose-specific consent (DPDP) | Separable consent flags (account+store to remind / eval-retention / caregiver-v2), uploaded via `PUT /v1/me/consents` post-login (§5); eval-retention default OFF. **(Deferred)** provenance logging of each grant returns with the engine plane's `consent_provenance` table |
| Data minimisation | v1: the source image is never persisted — nothing to auto-delete (§2.3, §7.1); we store the structured record, not the image; **only the phone number is PII**, encrypted + blind-indexed. **(Deferred)** eval crops, opt-in only, return with the engine plane |
| Identity confined to one database | v1: `userId` exists in the single `rxscan` database; there's no engine plane to keep it separate from. **(Deferred)** target: engine path keyed to client key + pseudonymous token, `userId` confined to a separate encrypted consumer DB |
| Encryption of health data | App-layer envelope encryption (per-user DEK wrapped by KMS/HSM); DB holds only ciphertext; no standing human decrypt access; audit-logged unwraps |
| Enforceable erasure/export | User record: delete/export **by `userId`**, erasure destroys the wrapped DEK (cryptographic erasure), tested to completeness — post-slice-A (§4). **(Deferred)** eval store delete/export **by token** returns with the engine plane; there's no eval store in v1 to erase |
| Minor consent | Server persistence of a child's record gated on guardian verification; local scan→verify path unaffected |
| Breach readiness | Access logs + audit trail; runbook is a Phase-3 deliverable |
| Diligence-ready provenance | Append-only consent log today. **(Deferred)** WORM backup + per-item eval-set provenance return with the engine plane |

---

## 9. Open technical questions

Mirrors the PRD's product open-Qs where they have an engineering dimension, plus new build-level ones.

1. **Vision-LLM provider + Data Processor terms** (PRD Q7): confirmed zero-retention/no-training, and Indian-health-data transfer terms, *before* any real prescription is sent. Blocks Phase 1.
2. **Cost per scan** at the chosen model (PRD Q2/Q8): drives free-tier cap and B2B price; instrument from the bake-off.
3. **Formulary source + refresh** *(deferred — no-op while formulary matching is disabled, §2.4)*: where the ~200K SKU catalog comes from, licensing, and update cadence, for whenever it's reintroduced.
4. **Preprocess pipeline** *(not yet built)*: on-device vs server-side deskew/crop/enhance trade-off (bandwidth + latency vs client complexity). Letterhead-strip must be server-side and pre-retention regardless — moot until eval-set retention exists again.
5. **Confidence thresholds:** the per-field and whole-image cutoffs that trigger forced-confirm vs honest-failure — set from bake-off data, not guessed.
6. **Idempotency + poll strategy** *(deferred)*: v1's `/extract` is single-request/response with no polling at all (not even "stays poll") — this question is moot until extraction is async again.
7. **Retention-token lifecycle** *(deferred)*: rotation, and export-format spec (DPDP portability) — no retention tokens exist in v1.
8. **When to introduce Redis:** define the concrete metering-contention metric that trips the trigger in §6.9 — applies once per-key metering (engine plane) exists to contend on.
9. ~~**Encryption model (PRD Q12):** envelope vs E2E?~~ **Resolved: envelope encryption (server-decryptable).** Recovers cleanly on a lost device (E2E cannot without escrow), and is legally sufficient — DPDP Rule 6 mandates encryption + key management, not zero-knowledge. Defensibility rests on the key controls in §7.1 (HSM master key, no standing decrypt access, audit logs), not on irreversibility. Unaffected by the single-DB move (§1, §5).
10. ~~**Account timing (PRD Q13):**~~ **Reopened and re-resolved: before scan — account-first (v0.4.3), reversing "at save" (v0.4.1).** Phone-OTP sign-in is now the *first* app step; scan/verify/save/schedule all run inside the resulting session, with **persistent login** (a stored session skips sign-in and opens straight to the dashboard; logout clears it). Engineering consequences of the reversal: the pre-login device-metering design (client key + Play Integrity attestation) and the unauthenticated-verify-state handling from v0.2.1 are **dropped** — every extraction call now happens with a session already established, so there's no unauthenticated intermediate state to design around. OTP failure still falls back to resend; the app has one auth state instead of two.
11. **OTP provider + abuse controls:** ~~SMS Data Processor choice~~ **vendor resolved: Gupshup, SMS only (no WhatsApp OTP)** — contract + DLT registration pending (see CHECKLIST); stub `000000` until then. Still open: OTP rate-limiting/lockout and defence against OTP-pumping / enumeration on the blind index.
12. **Key rotation:** DEK/master-key rotation policy and re-wrap procedure; refresh-token rotation and session lifetime.
13. **When does the engine plane come back?** *(new, v0.2.4-draft)* Concrete trigger for re-splitting into engine/consumer databases — the platformisation signal named in PRD §2.5 (partner API demand, B2B volume, or a retained eval set becoming a priority), not a date.

---

## 10. Build sequencing (maps to PRD §11 milestones)

| Phase | Engineering deliverables |
|---|---|
| **0 — Bake-off** | Eval harness; provider benchmark under de-identified data + zero-retention settings; confidence-threshold baseline; **Data Processor agreement** in motion |
| **1 — Extraction API + consumer backend** | Spring Boot; **single `rxscan` Postgres database** — stateless `/extract` (no job table, no queue, no formulary, no provenance log in v1; provider-pluggable vision call, Claude default, §2.5); **phone-OTP auth (blind-indexed phone), sign-in first with persistent login; encrypted prescription store (envelope encryption + KMS/HSM); prescription CRUD; delete/export by `userId`**. *(Deferred: engine Postgres — jobs `jsonb`, `SKIP LOCKED` queue, `pg_trgm` formulary, provenance; object storage w/ lifecycle expiry; client-key auth + metering; retention-token plumbing; letterhead-strip preprocess — returns with platformisation.)* |
| **2 — Android core** | **Phone-OTP sign-in first, with persistent login** → consent → capture (+offline queue) → verify (hard gate, empty-input flags, ask-doctor share) → schedule (AC/PC, HS, ongoing) → notify (exact-alarm onboarding + WorkManager fallback) → adherence log; **server-backed store with Room offline cache + sync**; purpose-specific consent; delete/export |
| **3 — Polish & safety** | Edge cases; minor-consent path; **erasure completeness test**; breach runbook; non-advisory listing copy review |
| **4 — Beta** | 20–30 users; instrument §9 metrics **including the confirmed-schedule audit** (the P0 safety metric — blocked until the engine plane/retained crops exist to sample against, §7.3) |

---

*This is a v1 design. The deliberate bias throughout is toward a minimal, contained server footprint — for v1, that means **one database, no engine plane at all**, because there's no partner/eval/metering surface to isolate from a single user-only product. The two-plane split — an engine plane that knows no user, and a single separate, encrypted consumer store for the `userId`-keyed record — is deferred, not abandoned: it's the design this doc keeps in full (§1, §5, §6) for the point the product platformises. In a health-data, DPDP-bound system, every byte of identity-linked data is a liability until it is encrypted, minimised, and provably erasable — which is exactly what the single consumer store makes it today, and what the consumer-plane split will make it again once there's an engine plane to separate it from.*
