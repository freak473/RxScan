-- rxscan_consumer V1 — consumer plane. The ONLY place a userId/phone lives.
-- Every medical field is inside an encrypted payload; nothing sensitive in
-- queryable columns (§5 invariant 2). No FK can reach the engine DB by design.

CREATE EXTENSION IF NOT EXISTS pgcrypto;      -- gen_random_uuid()

-- the only PII, minimised ---------------------------------------------------
CREATE TABLE app_user (                       -- "user" is reserved-ish; app_user is safer
    user_id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    phone_enc         BYTEA NOT NULL,          -- ciphertext; plaintext only in the OTP request
    phone_blind_idx   BYTEA NOT NULL UNIQUE,   -- keyed-HMAC of phone; the login lookup key
    dek_wrapped       BYTEA NOT NULL,          -- per-user data key, wrapped by the KMS master key
    is_minor_verified BOOLEAN NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- No name / email / address column exists. Login is by phone_blind_idx only.

-- confirmed prescription record (encrypted) ---------------------------------
CREATE TABLE prescription (
    rx_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    payload_enc  BYTEA NOT NULL,               -- envelope-encrypted JSON: medicines, slots,
                                               -- meal timing, duration, course dates
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_prescription_user ON prescription (user_id);

-- adherence history (encrypted) ---------------------------------------------
CREATE TABLE adherence_event (
    event_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    rx_id        UUID NOT NULL REFERENCES prescription(rx_id) ON DELETE CASCADE,
    payload_enc  BYTEA NOT NULL,               -- slot time + state {taken, skipped, snoozed}
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_adherence_user ON adherence_event (user_id);
CREATE INDEX idx_adherence_rx   ON adherence_event (rx_id);
