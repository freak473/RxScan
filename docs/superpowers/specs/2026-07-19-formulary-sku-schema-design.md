# Formulary SKU catalog — schema & ingestion design

**Date:** 2026-07-19
**Status:** Approved (brainstorm) → ready for implementation plan
**Plane:** Engine (server-side, identity-free — see `docs/rxscan-tech-design-v0_2_2.md` §5, §6.A)
**Source data:** `Extensive_A_Z_medicines_dataset_of_India.csv` (~256K rows, 97 MB)

## Purpose

Load an A–Z catalog of Indian medicine brands into the engine-plane Postgres so the
extraction worker can **fuzzy-match the name it read off a prescription against a real
catalog entry and raise that field's confidence** (and resolve LLM near-misses to a
canonical SKU). Nothing more.

This table is a **scribe aid, not an advisory source**. It confirms "this brand exists
as printed." It does not drive dosing, indications, substitutions, or warnings.

## Non-goals (explicit — enforced by column choice)

- **No ingredients / composition.** `short_composition*`, `generic`, side-effects, uses,
  chemical/therapeutic/action class, substitutes — all dropped from the CSV. Keeping them
  would invite advisory use and violate the CDSCO non-advisory invariant.
- **No catalog versioning / provenance beyond timestamps.** No `catalog_version`, no price,
  no pack size. `created_at` / `updated_at` only.
- **No on-device copy.** 200K rows stay server-side; extraction runs in the worker.

## What we store (per the CSV → column mapping)

| Column | Source | Notes |
|---|---|---|
| `brand_name` | CSV `name` | Medicine name **as printed**, incl. strength/form text (`Allegra 120mg Tablet`) |
| `manufacturer` | CSV `manufacturer_name` | e.g. `Sanofi India Ltd` |
| `strength` | **parsed from `brand_name`** | Strength token(s), `NULL` when the name has none |
| `form` | **parsed from `brand_name`** | `Tablet` / `Syrup` / `Cream` / `Injection` / … , `NULL` if unknown |
| `is_discontinued` | CSV `Is_discontinued` | `True`/`False` → boolean |
| `name_normalized` | derived from `brand_name` | Lowercased, punctuation-stripped, whitespace-collapsed. The match key. |

### Key decisions (brainstorm log)

1. **Strength is parsed from the brand name, not the composition.** The CSV has no clean
   standalone strength column; strength lives inside the name (`Allegra 120mg Tablet`) and
   inside `short_composition` (dropped as an ingredient field). Parsing the name keeps us
   ingredient-free. Rows with no strength token (`Ascoril LS Syrup`, `Anovate Cream`) get
   `strength = NULL`. The full strength text still lives in `brand_name` regardless.
2. **"Different strength ⇒ different row" is satisfied for free.** Because strength is part
   of the name, each strength variant is already a distinct `brand_name` = a distinct row.
   No separate strength-variant table (rejected: invents structure the source lacks — YAGNI).
3. **`is_discontinued` = the CSV's `Is_discontinued`, one boolean, factual.** No separate
   operational kill-switch column (not needed for a matching aid).
4. **Store `form`.** Cheap to parse, materially disambiguates same-brand-different-form
   matches. Aligns with the tech-design `formulary_sku.form`.
5. **Reconciliation with existing schema.** `formulary_sku` already exists in
   `engine/V1__init.sql` as `formulary_id · brand_name · generic · strength · form ·
   search_terms`. We **edit V1 in place** (it is committed but not yet applied to any live
   DB): add `manufacturer`, `is_discontinued`, `created_at`, `updated_at`; **drop `generic`**
   (ingredient); **replace `search_terms` with `name_normalized`** (same role — a normalized
   fuzzy-match key — but scoped to the name, not a salt haystack). Table name, `formulary_id`
   IDENTITY pk, and `strength`/`form` naming are kept to match existing engine conventions.

## Schema (final DDL — replaces the `formulary_sku` block in `engine/V1__init.sql`)

`pg_trgm` is already enabled at the top of that migration.

```sql
-- formulary reference catalog (~200K, static) -------------------------------
-- Matching aid only: confirms a brand exists as printed and raises field
-- confidence. NON-ADVISORY: no ingredients, indications, or substitutions.
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
CREATE INDEX idx_formulary_name_trgm
    ON formulary_sku USING gin (name_normalized gin_trgm_ops);

-- idempotent CSV load + dedup (e.g. CSV id 4 'Allegra 120mg' appears twice)
CREATE UNIQUE INDEX uq_formulary_name_mfr
    ON formulary_sku (name_normalized, manufacturer);

-- skip discontinued cheaply at match time
CREATE INDEX idx_formulary_active
    ON formulary_sku (name_normalized) WHERE is_discontinued = FALSE;
```

## Ingestion (one-off / re-runnable loader)

A loader reads the CSV and upserts into `formulary_sku`. Steps per row:

1. **Trim & keep** `name` → `brand_name`, `manufacturer_name` → `manufacturer`.
2. **Parse `strength`** from `brand_name` via regex over strength tokens
   (`\d+(\.\d+)?\s?(mg|mcg|g|ml|%|iu)\b`, possibly multiple joined with `+`). `NULL` if none.
3. **Parse `form`** from `brand_name` (and fall back to `pack_size_label`) against a known
   form vocabulary (Tablet, Capsule, Syrup, Suspension, Cream, Ointment, Gel, Injection,
   Drops, Solution, Inhaler, …). `NULL` if unmatched.
4. **`is_discontinued`** = `Is_discontinued == "True"`.
5. **`name_normalized`** = `lower(brand_name)`, strip punctuation, collapse whitespace.
   Computed in the loader (Java), not a Postgres generated column — the regex is cleaner in
   app code.
6. **Upsert** on the unique key: `INSERT … ON CONFLICT (name_normalized, manufacturer)
   DO UPDATE SET strength=…, form=…, is_discontinued=…, updated_at=now()`. This collapses
   the CSV's exact-duplicate rows and makes re-running the loader safe.

CSV caveats the loader must handle: quoted fields containing commas (use a real CSV parser,
not naive split), a handful of malformed manufacturer values, and stray blank `type` rows.

## Matching (how the extraction worker uses it)

```sql
SELECT formulary_id, brand_name, manufacturer, strength, form,
       similarity(name_normalized, :q) AS score
FROM formulary_sku
WHERE name_normalized % :q            -- trigram similarity above threshold
  AND is_discontinued = FALSE
ORDER BY score DESC
LIMIT 5;
```

- `:q` is the normalized form of the LLM's read of the drug name.
- Top score above threshold → boost that field's confidence; a strong exact hit resolves the
  read to a canonical `formulary_id`.
- **Revisit trigger (per tech design §6.A):** if beta shows formulary miss-rate is a top-2
  error source, add a phonetic layer via the already-enabled `fuzzystrmatch`
  (`dmetaphone`) before escalating to anything heavier. Out of scope here.

## Invariants this design must not break

- **Non-advisory:** the table returns identity/existence only — never an indication, dose
  recommendation, or "suggested" correction. No `suggested_value`-style column anywhere.
- **Engine plane, identity-free:** no `userId`, phone, or any PII in this table or its loader.

## Out of scope (future)

- The loader/worker wiring in Java (separate task).
- Phonetic / semantic match layers.
- Periodic catalog refresh cadence.
