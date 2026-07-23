-- rxscan_consumer V1 — consumer plane. The ONLY place a userId/phone lives.
-- Every medical field is inside an encrypted payload; nothing sensitive in
-- queryable columns (§5 invariant 2). No FK can reach the engine DB by design.

CREATE EXTENSION IF NOT EXISTS pgcrypto;      -- gen_random_uuid()

-- the only PII, minimised ---------------------------------------------------
CREATE TABLE users (                          -- bare "user" is reserved in Postgres
    user_id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    phone_enc         BYTEA NOT NULL,          -- ciphertext; plaintext only in the OTP request
    phone_blind_idx   BYTEA NOT NULL UNIQUE,   -- keyed-HMAC of phone; the login lookup key
    dek_wrapped       BYTEA NOT NULL,          -- per-user data key, wrapped by the KMS master key
    is_minor_verified BOOLEAN NOT NULL DEFAULT false,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- No name / email / address column exists. Login is by phone_blind_idx only.

-- consent log (append-only; latest row per purpose wins) ---------------------
-- FE holds consents locally pre-login and uploads them at signin; granted_at
-- is the DEVICE timestamp of the actual grant, created_at the server receipt.
CREATE TABLE user_consent (
    consent_id   BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id      BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    purpose      TEXT NOT NULL,                -- 'process' | 'notify' | 'retain_optin' | ...
    granted      BOOLEAN NOT NULL,             -- withdrawal = new row with false, never UPDATE
    granted_at   TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_consent_user ON user_consent (user_id);

-- user preferences (encrypted, exactly one row per user) --------------------
-- FE-owned JSON (meal times, toggles); server never parses it. Pushed after
-- login and on every change; pulled on device rehydrate.
CREATE TABLE user_preference (
    user_id      BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    payload_enc  BYTEA NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- confirmed prescription record (encrypted) ---------------------------------
CREATE TABLE prescription (
    rx_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    payload_enc  BYTEA NOT NULL,               -- envelope-encrypted JSON: medicines, slots,
                                               -- meal timing, duration, course dates
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_prescription_user ON prescription (user_id);

-- adherence history (encrypted) ---------------------------------------------
CREATE TABLE adherence_event (
    event_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    rx_id        UUID NOT NULL REFERENCES prescription(rx_id) ON DELETE CASCADE,
    payload_enc  BYTEA NOT NULL,               -- slot time + state {taken, skipped, snoozed}
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_adherence_user ON adherence_event (user_id);
CREATE INDEX idx_adherence_rx   ON adherence_event (rx_id);

-- updated_at maintenance ------------------------------------------------------
CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated           BEFORE UPDATE ON users           FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_user_consent_updated    BEFORE UPDATE ON user_consent    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_user_preference_updated BEFORE UPDATE ON user_preference FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_prescription_updated    BEFORE UPDATE ON prescription    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_adherence_event_updated BEFORE UPDATE ON adherence_event FOR EACH ROW EXECUTE FUNCTION set_updated_at();
