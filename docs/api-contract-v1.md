# RxScan API contract v1

FE ↔ BE contract for slice A. Backend spec: `docs/superpowers/specs/2026-07-23-consumer-api-v1-design.md`.
Base URL (emulator): `http://10.0.2.2:8080/`

## Contract rules (product invariants)

- **No `suggested_value` exists in any schema** (CDSCO: flag, don't correct).
- **Phone is the only PII.** Sent once per login flow; stored encrypted + blind-indexed.
- **`payload` is opaque to the server** — FE-owned JSON, stored encrypted, round-tripped verbatim.
- **Any `401` ⇒ FE clears the token and routes to signin.** No refresh tokens in v1.
- All errors: `{"error":{"code":"...","message":"..."}}`. Codes: `invalid_phone` 422 ·
  `invalid_otp` 401 · `unauthorized` 401 · `not_found` 404 · `payload_too_large` 413 (cap 256 KB) ·
  `invalid_payload` 422 (malformed JSON body, or `payload` isn't a JSON object) ·
  `invalid_consent` 422 (a consent's `purpose` isn't `process`/`notify`/`retain_optin`, or
  `granted_at` is missing — applies to `/v1/auth/otp/verify` and `PUT /v1/me/consents`) ·
  `otp_delivery_failed` 503 · `internal_error` 500 (uniform catch-all for unexpected server errors).

## Auth

### POST /v1/auth/otp/request
```bash
curl -X POST $BASE/v1/auth/otp/request -H 'Content-Type: application/json' \
  -d '{"phone":"9876543210"}'
```
`200 {}` — OTP sent (dev stub: nothing sent, the accepted code is `000000`). `422 invalid_phone`.

### POST /v1/auth/otp/verify
```bash
curl -X POST $BASE/v1/auth/otp/verify -H 'Content-Type: application/json' \
  -d '{"phone":"9876543210","otp":"000000","consents":[
        {"purpose":"process","granted":true,"granted_at":"2026-07-23T10:00:00+05:30"}]}'
```
`200 {"token":"<jwt>","user_created":true}` — 30-day Bearer JWT; send as
`Authorization: Bearer <jwt>` on every endpoint below. `consents` = everything the FE
held locally pre-login (purposes: `process` | `notify` | `retain_optin`; `granted_at` =
device-side grant time). `401 invalid_otp` · `422 invalid_consent`.

## Me

### PUT /v1/me/consents  (Bearer)
```bash
curl -X PUT $BASE/v1/me/consents -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"consents":[{"purpose":"notify","granted":true,"granted_at":"2026-07-23T11:00:00+05:30"}]}'
```
`204` — rows are append-only; withdrawal = new row with `granted:false`. `422 invalid_consent`.

### PUT /v1/me/preferences  (Bearer)
```bash
curl -X PUT $BASE/v1/me/preferences -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"payload":{"schema":1,"mealTimes":{"breakfast":"08:00","lunch":"13:00","dinner":"20:30"}}}'
```
`204` — upsert, exactly one blob per user. Push right after login and on every change.

### GET /v1/me/preferences  (Bearer)
`200 {"payload":{...},"updated_at":"..."}` · `404 not_found` if never set. Pull on device rehydrate.

**Preferences payload schema (FE-owned, additive-only):**
`{"schema":1,"mealTimes":{"breakfast":"HH:mm","lunch":"HH:mm","dinner":"HH:mm"}}`

## Prescriptions

### POST /v1/prescriptions  (Bearer)
```bash
curl -X POST $BASE/v1/prescriptions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"payload":{"schema":1,"meds":[{"name":"Augmentin 625","strength":"625mg",
       "slots":["morning","night"],"mealTiming":"after_food","durationDays":5,"prn":false}],
       "confirmedAt":"2026-07-23T10:30:00+05:30"}}'
```
`201 {"rx_id":"<uuid>","updated_at":"..."}` — deferred save: fire right after OTP verify succeeds.

### PATCH /v1/prescriptions/{rx_id}  (Bearer)
Same body. `200 {"updated_at":"..."}` · `404` (also for someone else's `rx_id`).

### GET /v1/prescriptions?since=<ISO8601>  (Bearer)
`200 {"prescriptions":[{"rx_id":"...","payload":{...},"created_at":"...","updated_at":"..."}]}`
`since` optional — absent ⇒ full pull (device rehydrate).

**Meds payload schema (FE-owned):**
`{"schema":1,"meds":[{"name","strength","slots":["morning"|"afternoon"|"night"],"mealTiming":"before_food"|"after_food"|null,"durationDays":int|null,"prn":bool}],"confirmedAt":ISO8601}`
There is no field for a system-suggested value, by design.

## Extraction (engine plane — unchanged, no user identity)

### POST /extract
Multipart image upload; no auth header today (client-key auth is a pending backend task).
Returns the flag-annotated extraction JSON the Verify screen renders. Errors:
`413` oversize (10 MB) · `415` bad type · `502` upstream vision failure · `503` vision unavailable.
The extraction result schema is documented in `docs/rxscan-tech-design-v0_2_3.md` §4.
