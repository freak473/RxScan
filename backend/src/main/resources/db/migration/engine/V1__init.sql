-- rxscan_engine V1 — engine plane. Keyed to token/client, NEVER a person.
-- docs/rxscan-tech-design-v0_2_3.md §5. No userId/phone may appear in this database.

CREATE EXTENSION IF NOT EXISTS pgcrypto;      -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS pg_trgm;       -- formulary fuzzy match
CREATE EXTENSION IF NOT EXISTS fuzzystrmatch; -- phonetic layer (§6.A revisit trigger)

-- client identity + metering ------------------------------------------------
CREATE TABLE client_key (
    client_key_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner          TEXT NOT NULL CHECK (owner IN ('self', 'partner')),
    hashed_key     BYTEA NOT NULL UNIQUE,          -- never store the raw key
    tier           TEXT NOT NULL DEFAULT 'free',
    rate_limit     INTEGER NOT NULL,               -- requests / window
    monthly_cap    INTEGER,                        -- NULL = uncapped
    status         TEXT NOT NULL DEFAULT 'active'
                     CHECK (status IN ('active', 'suspended', 'revoked')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE usage_meter (
    client_key_id  UUID NOT NULL REFERENCES client_key(client_key_id),
    window_start   TIMESTAMPTZ NOT NULL,           -- bucket start (e.g. truncated hour)
    count          BIGINT NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (client_key_id, window_start)
);

-- extraction jobs (transient, short TTL) ------------------------------------
CREATE TABLE extraction_job (
    extraction_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    client_key_id    UUID NOT NULL REFERENCES client_key(client_key_id),
    status           TEXT NOT NULL DEFAULT 'queued'
                       CHECK (status IN ('queued','processing','done','failed','expired')),
    idempotency_key  TEXT NOT NULL,
    result           JSONB,                         -- nested medications payload; purged on TTL
    retention_token  UUID,                          -- set only if promoted to retention
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    UNIQUE (client_key_id, idempotency_key)         -- dedupe retried uploads
);
CREATE INDEX idx_job_expiry  ON extraction_job (expires_at);   -- TTL sweep
CREATE INDEX idx_job_pending ON extraction_job (status, created_at)
    WHERE status IN ('queued','processing');                   -- SKIP LOCKED queue (§6.C)

-- retained eval set (opt-in only) -------------------------------------------
CREATE TABLE retained_item (
    item_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    retention_token  UUID NOT NULL,                 -- the DPDP delete/export key
    field_type       TEXT NOT NULL,                 -- 'name' | 'strength' | 'duration' | ...
    crop_ref         TEXT NOT NULL,                 -- object-store key; blob lives in the bucket
    extracted_value  TEXT,
    corrected_value  TEXT,                          -- USER's correction only, never system-proposed
    confidence       REAL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_retained_token ON retained_item (retention_token);

CREATE TABLE correction (
    correction_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    retention_token  UUID NOT NULL,
    extraction_id    UUID NOT NULL,                 -- soft ref; job may already be TTL-purged
    field_type       TEXT NOT NULL,
    model_value      TEXT,
    user_value       TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_correction_token ON correction (retention_token);

-- formulary reference catalog (~200K, static) -------------------------------
-- Matching aid only: confirms a brand exists as printed and raises a field's
-- confidence during extraction. NON-ADVISORY — no ingredients, indications, or
-- substitutions live here (docs/superpowers/specs/2026-07-19-formulary-sku-schema-design.md).
CREATE TABLE formulary_sku (
    formulary_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    brand_name       TEXT        NOT NULL,          -- as printed: 'Allegra 120mg Tablet'
    manufacturer     TEXT        NOT NULL,          -- 'Sanofi India Ltd'
    strength         TEXT,                          -- parsed from brand_name, NULL if none: '120mg'
    form             TEXT,                          -- parsed from brand_name: 'Tablet' | 'Syrup' | ...
    is_discontinued  BOOLEAN     NOT NULL DEFAULT FALSE,  -- = CSV Is_discontinued
    name_normalized  TEXT        NOT NULL,          -- lowercased, depunctuated, space-collapsed match key
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- fuzzy brand-name match (the whole point of the table)
CREATE INDEX idx_formulary_name_trgm ON formulary_sku USING gin (name_normalized gin_trgm_ops);
-- idempotent CSV load + dedup (e.g. CSV id 4 'Allegra 120mg' appears twice)
CREATE UNIQUE INDEX uq_formulary_name_mfr ON formulary_sku (name_normalized, manufacturer);
-- skip discontinued cheaply at match time
CREATE INDEX idx_formulary_active ON formulary_sku (name_normalized) WHERE is_discontinued = FALSE;

-- consent + provenance (append-only, audit-grade) ---------------------------
CREATE TABLE consent_provenance (
    event_id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    retention_token  UUID NOT NULL,
    purpose          TEXT NOT NULL CHECK (purpose IN ('process','retain','backup')),
    granted          BOOLEAN NOT NULL,              -- captures denials too, not just grants
    source           TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_provenance_token ON consent_provenance (retention_token);

-- updated_at maintenance ------------------------------------------------------
CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_client_key_updated         BEFORE UPDATE ON client_key         FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_usage_meter_updated        BEFORE UPDATE ON usage_meter        FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_extraction_job_updated     BEFORE UPDATE ON extraction_job     FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_retained_item_updated      BEFORE UPDATE ON retained_item      FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_correction_updated         BEFORE UPDATE ON correction         FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_formulary_sku_updated      BEFORE UPDATE ON formulary_sku      FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER trg_consent_provenance_updated BEFORE UPDATE ON consent_provenance FOR EACH ROW EXECUTE FUNCTION set_updated_at();
-- Immutability is policy-enforced (§6.F): once an app DB role exists, run
--   REVOKE UPDATE, DELETE ON consent_provenance FROM rxscan_app;
-- Token-scoped erasure is itself a logged INSERT, never an UPDATE/DELETE.
