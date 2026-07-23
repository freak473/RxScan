-- Extraction jobs — one row per prescription image sent to /extract. Sign-in is now the first
-- step, so extraction runs with a user context and each job links to its user. The extracted
-- medical payload is envelope-encrypted (result_enc), exactly like prescription.payload_enc — never
-- stored as plaintext (data-minimisation / DPDP invariant). Rows are transient: they carry a TTL
-- (expires_at) and are swept once expired; result_enc is purged with them.
--
-- NOTE: this is schema only. The current /extract is still stateless — wiring it to authenticate,
-- insert a job, and write result_enc is a separate change. The table sits empty until then.

CREATE TABLE extraction_job (
    extraction_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          BIGINT NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    status           TEXT NOT NULL DEFAULT 'queued'
                       CHECK (status IN ('queued','processing','done','failed','expired')),
    idempotency_key  TEXT NOT NULL,                 -- client-supplied; dedupes retried uploads
    result_enc       BYTEA,                         -- envelope-encrypted medications; NULL until 'done'
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ NOT NULL,
    UNIQUE (user_id, idempotency_key)               -- one job per (user, retried upload)
);
-- TTL sweep of expired jobs
CREATE INDEX idx_job_expiry  ON extraction_job (expires_at);
-- pending-work scan (queue drain via SKIP LOCKED, when async processing is wired)
CREATE INDEX idx_job_pending ON extraction_job (status, created_at) WHERE status IN ('queued','processing');

CREATE TRIGGER trg_extraction_job_updated BEFORE UPDATE ON extraction_job FOR EACH ROW EXECUTE FUNCTION set_updated_at();
