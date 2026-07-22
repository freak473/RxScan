# RxScan — Deterministic Prescription Parser (design)

**Date:** 2026-07-20
**Status:** Draft for review
**Scope:** Backend, `com.rxscan.backend.extraction.parse`. Deterministic parsing layer only —
no live vision-LLM call, no worker/queue wiring.

## Context

Why this exists: extracting a handwritten prescription into structured medication data must
**not** cost two model calls (a vision pass *then* an LLM parsing pass). Model tokens are the
dominant unit cost (`rxscan-tech-design-v0_2_2.md` §7.4, planning figure ≈ ₹1/scan), so a second
LLM pass to interpret dose notation would roughly double it for work that is fully deterministic.

The good news: this is already the documented architecture. §2.3 specifies **one** vision-LLM call
emitting strict JSON, followed by deterministic steps — a *frequency grammar parser*, *formulary
fuzzy-match*, *strength anomaly flagging*, and *per-field confidence scoring*. None of that
deterministic layer exists in code yet. The only formulary code today
(`MedicineNameParser.java`) parses the **catalog CSV**, not prescriptions.

This spec designs that deterministic layer. It consumes the vision model's raw, as-written fields
(supplied here as test fixtures) and produces the §4 result schema, complete with the CDSCO-safe
flags. Building it now de-risks the product's core accuracy moat and is independent of which vision
provider is eventually chosen.

**Intended outcome:** a pure, table-tested Java module that turns raw per-medicine strings into a
structured, confidence-scored, flag-annotated `MedParseResult` — the single source of truth the
Verify screen renders.

## Non-negotiable invariants (this module enforces them)

- **Flag, don't correct.** A flag names a field and asks for a re-check. It carries **no
  `suggested_value`** and never pre-fills a value. Enforced by the type system: `Flag` has no value
  field (`rxscan-tech-design-v0_2_2.md` §4, CLAUDE.md).
- **Non-daily frequencies force a confirm.** Weekly / alternate-day misread as daily is the
  highest-harm error; any non-daily `pattern` always flags.
- **No inferred fields.** Meal timing, duration, and slots are populated only from what the model
  read. Absent → `null`/`UNSPECIFIED`, never guessed.
- **Never rewrite the read text.** A formulary near-miss resolves a `formulary_id` and raises
  confidence; it does **not** replace the displayed name/strength (that would be a banned
  suggested_value). The user always sees exactly what was read.

## Input contract — `VisionMedRaw`

What the vision call will eventually emit per medicine, all values **as written**. Modeled now as
fixtures (JSON) so the parser is built and tested with no live API.

```
VisionMedRaw {
  name         : String        // "Augmentin 625"        (verbatim)
  strength     : String?       // "625mg"                (verbatim, may be null)
  doseNotation : String        // "1-0-1", "BD", "1 tab twice a day"
  duration     : String?       // "5 days", "x1 week", "continue"
  meal         : String?       // "after food", "AC", null
  imageRegion  : Box?          // crop coords, passed through untouched
  confidence   : FieldConfidence   // per-field model confidence, each 0.0..1.0
}
FieldConfidence { name, strength, doseNotation, duration, meal : double }
```

A prescription is `List<VisionMedRaw>`. The parser processes each independently.

## Output contract — `MedParseResult`

Mirrors `rxscan-tech-design-v0_2_2.md` §4. Illustrative shape:

```
MedParseResult {
  drug        : { value: String, formularyId: Long?, confidence: double }
  strength    : { value: String?, confidence: double }
  frequency   : { raw: String, slots: Slots, pattern: Pattern, confidence: double }
  mealTiming  : { value: Meal?, confidence: double }     // BEFORE|AFTER|WITH|null
  duration    : { type: DurationType, days: Integer?, confidence: double }
  flags       : List<Flag>                                // NO value field, ever
}

Slots       { morning:int, noon:int, night:int, bedtime:int }   // dose count per slot
Pattern     = DAILY | WEEKLY | ALTERNATE_DAY | PRN | STAT
Meal        = BEFORE | AFTER | WITH
DurationType= DAYS | ONGOING | UNSPECIFIED
Flag        { field: FieldName, reason: FlagReason }            // field only — no suggested value
```

## Components

Each is a single-purpose unit with a well-defined interface. All are pure functions except
`FormularyMatcher` (reads Postgres).

### 1. `FrequencyGrammar.parse(doseNotation) -> FrequencyResult`

Pure. Recognizes the notations enumerated in §2.3. Case-insensitive; tolerant of spaces and
separators (`-`, `–`, `x`, `/`).

**Positional (slot counts):**
| Input | slots (m-n-ni-bed) | pattern | notes |
|---|---|---|---|
| `1-0-1` | 1-0-1-0 | DAILY | canonical 3-slot morning/noon/night |
| `1-1-1` | 1-1-1-0 | DAILY | |
| `0-0-1` | 0-0-1-0 | DAILY | |
| `2-0-2` | 2-0-2-0 | DAILY | dose count per slot preserved |
| `½-0-½` / `0.5-0-0.5` | 0.5-0-0.5-0 | DAILY | fractional counts allowed |
| `1-1` (2-slot) | 1-0-1-0 | DAILY | **morning+night by convention**, confidence capped (ambiguous) |
| `1-0-0-1` (4-slot) | 1-0-0-1 | DAILY | 4th position = bedtime |

**Latin / abbreviations:**
| Input | expansion | pattern |
|---|---|---|
| `OD` / `once daily` | 1-0-0-0 | DAILY |
| `BD` / `BID` / `twice daily` | 1-0-1-0 | DAILY |
| `TDS` / `TID` / `thrice daily` | 1-1-1-0 | DAILY |
| `QID` / `QDS` | 1-1-1-1 | DAILY |
| `HS` / `at bedtime` | 0-0-0-1 | DAILY (bedtime slot) |
| `SOS` / `PRN` / `as needed` | 0-0-0-0 | PRN (no schedule) |
| `STAT` / `immediately` | 0-0-0-0 | STAT (one-off) |

**Non-daily:**
| Input | pattern | note |
|---|---|---|
| `weekly` / `once a week` / `OW` | WEEKLY | always flags (force confirm) |
| `EOD` / `alternate day` / `alt day` | ALTERNATE_DAY | always flags |

**Result:** `FrequencyResult { slots, pattern, recognized:boolean, ambiguous:boolean, confidence }`.
- Fully recognized, unambiguous, daily → `confidence` starts from the model's `doseNotation`
  confidence, no penalty.
- `ambiguous` (2-slot shorthand) → confidence capped at a ceiling constant (`AMBIGUOUS_FREQ_CAP`).
- `recognized == false` → confidence forced low; `MedParseResult` gets a `FREQ_UNRECOGNIZED` flag.

### 2. `FormularyMatcher.match(name, strength) -> FormularyMatch`

Reads Postgres (`rxscan_engine.formulary_sku`). Reuses `MedicineNameParser.normalize(name)` for the
match key, and queries the existing `idx_formulary_name_trgm` GIN index over **active** SKUs
(`is_discontinued = false`).

```sql
SELECT formulary_id, brand_name, strength,
       similarity(name_normalized, :key) AS score
FROM formulary_sku
WHERE is_discontinued = false
  AND name_normalized % :key           -- trigram candidate filter
ORDER BY score DESC
LIMIT 5;
```

Thresholds (tunable constants; §9.5 leaves exact values open):
- `score >= MATCH_HIGH` (default 0.55) → **resolve** `formularyId`; boost `drug.confidence` toward
  the model confidence blended with `score`. Displayed name is unchanged.
- `MATCH_LOW <= score < MATCH_HIGH` → keep as read, no `formularyId`, medium confidence.
- `score < MATCH_LOW` (default 0.30) → no match; `formularyId = null`, no confidence boost. Combined
  with a low model `name` confidence this yields a `NAME_LOW_CONFIDENCE` flag.

**Strength cross-check** (only when a SKU is resolved and both strengths are present): compare read
`strength` to `sku.strength` using the same normalization as `MedicineNameParser.parseStrength`
would produce (e.g. `"625 mg"` ≡ `"625mg"`). Mismatch → `STRENGTH_ANOMALY` flag. This never edits
the read strength.

Returns `FormularyMatch { formularyId:Long?, score:double, skuStrength:String?, matched:boolean }`.

### 3. `MealTimingParser.parse(meal) -> Meal?`

Pure. `AC` / `before food` / `empty stomach` → `BEFORE`; `PC` / `after food` → `AFTER`;
`with food` → `WITH`; anything unrecognized or `null` → `null`. Never inferred from dose notation.

### 4. `DurationParser.parse(duration) -> DurationResult`

Pure. `"5 days"` / `"x5d"` / `"5/7"` → `{DAYS, 5}`; `"1 week"` / `"x1w"` → `{DAYS, 7}`;
`"2 weeks"` → `{DAYS, 14}`; `"continue"` / `"ongoing"` / `"regular"` → `{ONGOING, null}`;
absent/unparseable → `{UNSPECIFIED, null}`. Bounds check 1–60 days (matches the Android numeric
flag validator); out of range → `{UNSPECIFIED, null}` + `DURATION_UNCLEAR` flag.

### 5. `MedicationParser.parse(VisionMedRaw) -> MedParseResult` (orchestrator)

Calls the four units, assembles `MedParseResult`, and applies the **flag policy**:

| Flag reason | Trigger |
|---|---|
| `FREQ_UNRECOGNIZED` | `FrequencyResult.recognized == false` |
| `FREQ_NON_DAILY` | `pattern ∈ {WEEKLY, ALTERNATE_DAY}` — always (force confirm) |
| `STRENGTH_ANOMALY` | resolved SKU strength conflicts with read strength |
| `STRENGTH_UNREADABLE` | `strength == null` or model strength confidence `< CONF_MIN` |
| `NAME_LOW_CONFIDENCE` | no formulary match **and** model name confidence `< CONF_MIN` |
| `DURATION_UNCLEAR` | duration present but unparseable / out of range |
| `FIELD_LOW_CONFIDENCE` | any other field's model confidence `< CONF_MIN` |

`CONF_MIN`, `MATCH_HIGH/LOW`, `AMBIGUOUS_FREQ_CAP` are named constants in one `ParserThresholds`
class, documented as tunable pending the §9.5 confidence-threshold study. No flag ever carries a
suggested value — enforced structurally (`Flag` has only `field` + `reason`).

## Data flow

```
VisionMedRaw (fixture)
   │
   ├─ FrequencyGrammar.parse(doseNotation) ─► FrequencyResult
   ├─ FormularyMatcher.match(name,strength) ─► FormularyMatch   (Postgres, pg_trgm)
   ├─ MealTimingParser.parse(meal)          ─► Meal?
   ├─ DurationParser.parse(duration)        ─► DurationResult
   │
   └─ MedicationParser assembles + applies flag policy ─► MedParseResult
```

## Testing

TDD, tests first. Uses the Testcontainers Postgres already wired in commit `377aac0`.

- **`FrequencyGrammarTest`** — JUnit parameterized table covering every row above, plus junk input
  (`"??"`, `""`, `"1-2-3-4-5"`) → `recognized=false`. This is the highest-value test surface.
- **`MealTimingParserTest` / `DurationParserTest`** — parameterized recognized + unrecognized cases.
- **`FormularyMatcherTest`** — seeds a small known formulary (e.g. `Augmentin 625 Tablet`,
  `Pantocid 40mg`, `Dolo 650`) into a Testcontainers DB; asserts exact hit resolves `formularyId`,
  near-miss (`"Augmentn 625"`) resolves above `MATCH_HIGH`, unknown (`"Xyzzy"`) yields no match,
  and a strength mismatch raises `STRENGTH_ANOMALY`.
- **`MedicationParserTest`** — end-to-end over fixtures matching the design's demo meds (Augmentin
  clean, Pantocid strength-unreadable, Ascoril duration-unclear, Dolo 650 PRN). Asserts the exact
  `flags[]` set and that no flag has a value.

## Out of scope (explicitly)

- The live vision-LLM call, prompt, provider choice, Data Processor agreement, zero-retention terms.
- The async `extraction_job` worker / queue (`SKIP LOCKED`), image preprocessing, letterhead strip.
- Persisting results, the retention/correction tables, and the HTTP endpoint.
- Any Android change. Verify already renders these fields against `MockData`; wiring the real result
  is a later step.

## Open questions

- Exact threshold values (`CONF_MIN`, `MATCH_HIGH/LOW`) — deferred to the §9.5 confidence study;
  shipped as documented constants and tuned against a labeled eval set later.
- Whether `bedtime` should surface as its own slot in the Android Verify UI or fold into "night"
  for display — a UI concern; the parser keeps them distinct regardless.
