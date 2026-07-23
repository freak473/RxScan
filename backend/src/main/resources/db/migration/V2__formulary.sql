-- Formulary catalogue — a brand-name matching aid, not clinical content. Re-introduced into the
-- single-DB v1 schema (the former engine plane owned it; see the deleted engine/V1__init.sql).
-- Only name + manufacturer + parsed strength/form + discontinued flag are stored: no ingredients,
-- indications, or substitutions (non-advisory invariant). Confirming a brand exists raises
-- extraction confidence; it never rewrites the read text.

CREATE EXTENSION IF NOT EXISTS pg_trgm;        -- fuzzy brand-name match (similarity / % operator)

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

CREATE TRIGGER trg_formulary_sku_updated BEFORE UPDATE ON formulary_sku FOR EACH ROW EXECUTE FUNCTION set_updated_at();
