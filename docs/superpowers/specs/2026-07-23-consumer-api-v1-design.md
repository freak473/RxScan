# Consumer API v1 — auth, consents, preferences, prescriptions (slice A)

**Date:** 2026-07-23 · **Status:** approved design, pre-implementation
**Sources:** tech design §4 (contract), §6.G/§6.H (stores); CLAUDE.md invariants; user decisions in-session.

## Goal

Give the FE a real consumer plane to hit: phone-OTP login (stubbed OTP, Gupshup-ready),
consent upload at login, user preferences (meal times) sync, prescription save/edit/sync.
Everything the FE store holds must reach the BE after login. Design the FE store
(Room + DataStore) and publish a contract doc the FE builds against.

## Non-goals (named follow-ups)

Refresh tokens, per-phone/IP rate limiting, `DELETE /v1/me`, `GET /v1/me/export` (DPDP
launch-blockers, not integration blockers), adherence API, alarm scheduling, WhatsApp OTP
(explicitly excluded by product decision), real KMS (env-var master key for now).

## API contract (all JSON, path-versioned `/v1`)

| # | Endpoint | Auth | Request → Response |
|---|---|---|---|
| 1 | `POST /v1/auth/otp/request` | — | `{phone}` → `200 {}` · `422 invalid_phone` |
| 2 | `POST /v1/auth/otp/verify` | — | `{phone, otp, consents:[{purpose, granted, granted_at}]}` → `200 {token, user_created}` · `401 invalid_otp` |
| 3 | `PUT /v1/me/consents` | Bearer | `{consents:[…]}` → `204` |
| 4 | `POST /v1/prescriptions` | Bearer | `{payload:<opaque JSON>}` → `201 {rx_id, updated_at}` |
| 5 | `PATCH /v1/prescriptions/{rx_id}` | Bearer | `{payload}` → `200 {updated_at}` · `404` |
| 6 | `GET /v1/prescriptions?since=<ISO8601>` | Bearer | `200 {prescriptions:[{rx_id, payload, created_at, updated_at}]}` — `since` optional; absent ⇒ full pull |
| 7 | `PUT /v1/me/preferences` | Bearer | `{payload:<opaque JSON>}` → `204` (upsert; exactly one row per user) |
| 8 | `GET /v1/me/preferences` | Bearer | `200 {payload, updated_at}` · `404` if never set |

- **Server never parses `payload`.** It envelope-encrypts the FE's confirmed-meds JSON
  (AES-GCM with the per-user DEK) into `prescription.payload_enc` and round-trips it on
  sync. No medical data is server-interpretable.
- Payload schema is **FE-owned**, documented in the contract doc:
  `{schema:1, meds:[{name, strength, slots, mealTiming, durationDays, prn}], confirmedAt}`.
- Preferences payload likewise FE-owned + server-opaque: `{schema:1, mealTimes:{breakfast,lunch,dinner}, …}`
  → encrypted into `user_preference.payload_enc` (new 1:1 table; keeps `users` a pure identity
  row and gives preferences their own `updated_at` for sync).
- Errors: uniform `{error:{code, message}}` — codes `invalid_phone`, `invalid_otp`,
  `unauthorized`, `not_found`, `payload_too_large` (cap 256 KB).
- Consents are append-only rows in `user_consent` (withdrawal = new row, `granted=false`);
  `granted_at` is the device-side grant time, `created_at` server receipt.
- Purposes v1: `process` | `notify` | `retain_optin`.

## App-flow alignment (login is LATE — after verify/mealtimes)

Flow: welcome → consent → capture → extract → verify → mealtimes → **signin/otp** → notifperm → today.
- Everything before signin needs **no user auth**: consents sit in DataStore, `/extract` is
  engine-plane (no user identity), verify/mealtimes are local.
- The **prescription save is deferred**: confirmed meds are held locally (Room,
  `pendingSync=true`) and `POST /v1/prescriptions` fires right after OTP verify succeeds —
  along with `PUT /v1/me/preferences` (meal times set on the mealtimes screen, pre-login).
  **Everything the FE store holds is on the server once login completes**; preferences also
  re-push on every later change and pull on device rehydrate.
- `notify` consent arrives after login (notifperm screen) via `PUT /v1/me/consents`;
  `process`/`retain_optin` piggyback on the verify call.
- **Contract rule: any `401` ⇒ FE clears the token and routes to signin** (re-OTP; no
  refresh tokens in this slice). Pending local data stays in Room and re-pushes after login.

## OTP delivery — provider strategy (mirrors the vision-provider pattern)

```
interface OtpSender { void send(String phone, String otp); }
```

- `rxscan.otp.provider=stub` (default) → `StubOtpSender`: sends nothing; the accepted code
  is `rxscan.auth.dev-otp` (default `000000`). Request endpoint still validates the phone.
- `rxscan.otp.provider=gupshup` → `GupshupOtpSender` (coded now, dormant until the Gupshup
  contract + DLT registration close — see CHECKLIST):

```java
// POST https://enterprise.smsgupshup.com/GatewayAPI/rest
// form params: method=SendMessage, send_to=<phone>, msg=<dlt-approved template with OTP>,
//   msg_type=TEXT, userid=${rxscan.otp.gupshup.user-id}, password=${...password},
//   v=1.1, auth_scheme=plain, format=json,
//   principalEntityId=${...principal-entity-id}, dltTemplateId=${...template-id}
// Non-2xx or Gupshup "error" status → OtpDeliveryException → 503 to client.
```

- SMS only — WhatsApp OTP explicitly out (product decision, even though cheaper).
- **Tests never call Gupshup** (same rule as no-live-AI-calls): unit-test request shape with
  `MockRestServiceServer`; the stub path covers integration tests.

## Auth mechanics

- Verify flow: blind-index lookup (`HMAC-SHA256(phone, RXSCAN_BLIND_IDX_KEY)`); first login
  creates `users` row — phone AES-GCM-encrypted with master key, fresh 256-bit DEK wrapped
  by `RXSCAN_MASTER_KEY`; consents from the request are inserted.
- JWT: HS256 via **jjwt**, 30-day expiry, secret `RXSCAN_JWT_SECRET`. Enforced by a plain
  `HandlerInterceptor` on consumer `/v1/**` routes — no spring-security framework.
- **`sub` = `users.public_id`** — new `UUID UNIQUE NOT NULL DEFAULT gen_random_uuid()`
  column added to V1 (CLAUDE.md rule: sequential `user_id` never leaves the DB layer).
- All keys are env vars; KMS swap later touches only `CryptoService`.

## Backend layout (consumer datasource, `JdbcClient`, no ORM)

```
auth/         AuthController, OtpService, OtpSender{Stub,Gupshup}, JwtService,
              JwtInterceptor, CryptoService, UserRepository
consent/      ConsentController, ConsentRepository
preference/   PreferenceController, PreferenceRepository
prescription/ PrescriptionController, PrescriptionRepository
```

## FE bindings + store

- `data/net/`: `AuthApi`, `MeApi` (consents + preferences), `PrescriptionApi` beside
  `ExtractionApi`; shared OkHttp; auth interceptor injects `Bearer` from DataStore.
- **DataStore** (Jetpack Preferences): JWT, phone, meal times, pre-login consents (FE holds
  consents + meal times locally until login, then uploads — accepted risk: storage wipe
  pre-login loses nothing, since the server holds no user data yet).
- **Room**: `PrescriptionEntity(localId PK, rxId nullable, payloadJson, pendingSync, updatedAt)`
  + DAO; disposable cache of the server record, rehydrated via `GET ?since=`. `rxId` is null
  and `pendingSync=true` until the post-login save assigns the server id. Deliberately
  unnormalized; med/dose tables arrive with the alarm+adherence slice when SQL needs them.
- Signin/OTP screens rewire from mock to real calls (`RxScanNav.kt`).

## Contract doc deliverable

`docs/api-contract-v1.md`: every endpoint with curl-able examples, error table, both FE-owned
payload schemas (meds + preferences), the existing `POST /extract` documented as-is, and CDSCO/DPDP invariants
stated as contract rules (no `suggested_value` field exists in any schema; phone is the
only PII; payload is opaque to the server).

## Testing

MockMvc + local Postgres for controllers/repos; crypto round-trip unit tests; jjwt
expiry/tamper tests; `MockRestServiceServer` for Gupshup request shape. No live SMS, no
live AI, ever.
