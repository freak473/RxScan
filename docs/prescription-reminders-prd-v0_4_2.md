# PRD: RxScan (working title)
### Scan a prescription → verified medication reminders

**Version:** 0.4.3 (final — requirements closed)
**Owners:** Maalik, [friend]
**Status:** Requirements closed — build (pair with `RxScan-v2-design-v3.html` and `rxscan-tech-design-v0_2_3.md`)
**Last updated:** 23 July 2026

> **Changes in v0.4.3:** Reopened and re-closed **Q13 — account-first, not account-at-save**: phone-OTP sign-in now runs *first*, before capture (flow: sign-in → OTP → consent → capture → extract → verify → meal times → notification permission → dashboard). Added **persistent login** — a returning user with a valid session skips sign-in entirely and lands straight on the dashboard; logging out clears the session and returns to sign-in. Tradeoff reversal from v0.4.1: we now ask for the phone number before the user has seen the extraction work (a small upfront friction cost) in exchange for a session that survives app restarts and an onboarding that no longer re-runs on every launch (§6, §8, §11, §13 Q13). Also reflects a **v1 backend simplification to a single database**: the v0.4 two-plane engine/consumer split is **deferred, not abandoned**, until the product platformises (partner API, metering, retained eval set) — no product-facing behavior changes from this (§2.5, §7, §8, §11). Vision extraction now runs on **Anthropic Claude** (default `claude-sonnet-5`) behind the same pluggable provider design named in §10 — a backend swap, not a product change (§7).
>
> **Changes in v0.4.2:** Added the **notification-permission strategy** (§6 step 6 as of v0.4.3's renumbering — step 4 at the time this was written, §8): POST_NOTIFICATIONS is a runtime permission on Android 13+ and is requested with a primer immediately after OTP at the save moment (visible purpose; the system dialog is a two-refusal resource, never spent at app launch); exact-alarm access is the separate, skippable second step (a Settings deep-link, not a dialog); battery-optimization exemption is asked contextually later; denial still saves and schedules, with a persistent recovery banner on Today. Design updated with the priming screen.
>
> **Changes in v0.4.1:** Closed Q13 — **account at save, not before scan**: scan/verify run pre-login; phone-OTP is prompted at the "Set my reminders" moment, so the first-scan magic (the product's proof) happens before any ask, and the ask carries a visible purpose ("so your reminders survive a phone change"). Restored the §7 **Unit cost & model routing** and **Model strategy** sections and the Q2 resolution that were dropped in the v0.4 fork (the v0.4 base predated that edit). Design updated to match (`RxScan-v2-design-v3.html`).
>
> **Changes in v0.4:** Reversed the local-only storage decision. **Phone + OTP is now the primary auth** (replacing the optional Google sign-in). **The user's prescription data is stored server-side, fully encrypted, keyed to a pseudonymous `userId`.** **The only personal detail retained is the phone number**, itself stored encrypted with a keyed-HMAC blind index for login lookup — no name, email, or other PII. The **server is now the system of record**; the device keeps a local cache so reminders still fire offline. The **extraction engine stays client-keyed and identity-free** (§7) so the platform asset (§2.5) remains separable — identity lives only in the consumer plane. Encryption model = **per-user envelope encryption under a KMS/HSM master key** (server-decryptable) — chosen over end-to-end because it recovers cleanly on a lost device and is sufficient under DPDP Rule 6, which mandates encryption + key management, not zero-knowledge (§13 Q12). Updated §2.5, §3.2, §4, §6, §7, §8, §12.5, §13.
>
> **Changes in v0.3:** Added §2.5 (platform thesis) and §12.5 (business model: no ads, free safety core, Pro tier, B2B API). Added optional Google sign-in (never gating the scan) and the **send-to-your-doctor** share feature. Resolved all 21 review anomalies from the v0.2 audit — resolution log in Annex A. Key resolutions: pseudonymous retention token (deletion rights now enforceable), keyed partner-ready API (never anonymous-public), "ongoing" duration type for chronic regimens, day-1-=-scan-day date convention, a safety metric (post-verification audit error rate), AC/PC offsets defined, bedtime slot for HS, weekly/alternate-day dosing with mandatory confirm, English/Latin-script scope stated, Android 14+ exact-alarm risk named, offline capture queue, counsel review moved to Phase 0–1, bake-off data-handling rules, discreet notifications, minor-consent mechanics.
>
> **Changes in v0.2:** Added regulatory-posture section (§3: CDSCO/SaMD, DPDP) and threaded it through. Reframed as a *non-advisory transcription + reminder tool*. Strength "auto-correction" downgraded to flag-only. Verification made a hard gate. Local-only storage. Two-layer opt-in consent, letterhead/PII stripping, self-service delete/export.

---

## 1. Problem

After a doctor consult, patients in India walk out with a prescription — ~90% of them handwritten — and are expected to self-manage a multi-drug regimen: which medicine, what dose, how many times a day, before or after food, for how long. People forget doses, take medicines at the wrong time relative to meals, and abandon courses midway (especially antibiotics). Medication non-adherence is estimated at ~50% globally and is a major driver of treatment failure.

Existing reminder apps (Medisafe, MyTherapy, 1mg/PharmEasy adherence features) require **manual entry** of each medicine, dose, and schedule. This friction kills adoption at the exact moment of need — a sick person standing outside a clinic will not type in six medicines.

## 2. Solution

An Android-first app where the user photographs their prescription. The app extracts the regimen (drug, strength, frequency, meal timing, duration), shows a **verification screen** where the user confirms or corrects each field against the original image, and then schedules local notifications for every dose — auto-stopping when the course ends.

**Core insight:** the product is not OCR and not reminders. It is an *extraction + verification* system. We will never hit 100% on handwritten prescriptions; the product's job is high extraction accuracy plus a UX that makes the remaining errors impossible to silently get wrong.

**Regulatory framing insight:** the product is a *scribe*, not an *advisor*. It records and reschedules what the doctor already wrote; it never diagnoses, recommends, substitutes, or interprets. This is not just a positioning choice — it is what keeps us outside India's Software-as-a-Medical-Device (SaMD) licensing regime (see §3). The user, not the app, is always the medical decision-maker; the verification gate is the mechanism that makes this true.

## 2.5 Platform thesis

We build the consumer app to win users and doctors, but we architect for a second outcome: **the extraction engine, eval set, and doctor network are standalone assets** that a large health player (e-pharmacy, hospital chain, insurer, health super-app) could license or acquire. What such an acquirer wants is not the reminder app — it is:

1. **The eval set + published accuracy benchmark.** Labeled handwritten Indian prescriptions with ground truth, grown continuously (§10). Anyone can call a vision LLM; nobody can shortcut this dataset. Every prescription in it carries documented consent (diligence-ready provenance log). It is also what eventually makes a **fine-tuned, self-hosted extraction model** possible (§7 model strategy) — a future asset whose value derives from this data, and which unlocks India-residency/on-prem inference for B2B.
2. **The extraction API as a product.** Keyed, metered, documented, versioned, with a sandbox — designed so a pharmacy or health app could integrate in a week. Never anonymous-public; always authenticated clients.
3. **The doctor-recommendation network.** A distribution channel every e-pharmacy covets and none has.

**Separability principle:** the engine, eval set (opt-in corrections only), and API business are structured to be transferable *without* the consumer data. In v0.4 the user's prescription record is stored server-side, but it is **encrypted and keyed to a pseudonymous `userId`** (§7, §8). Under envelope encryption it remains a distinct asset that can be carved out or deleted on a change of control; under the E2E/zero-knowledge option (§13) it is ciphertext an acquirer cannot read. Either way, the transferable assets are the engine, eval set, and API — not the consumer record. Under the DPDP Act, consent is purpose-specific and does not automatically survive a change of control for new purposes, so keeping the consumer record separate and encrypted is what makes a clean exit possible. We never soften consumer privacy promises to make a future sale easier; the sellable asset is the engine, not the users. **v1 note:** the engine plane (extraction as a keyed, metered, separately-stored service) is **deferred, not built** — v1 ships a single, users-only database and a stateless extraction call (nothing persisted server-side pre-verification). The separability principle above is the target architecture for when the product platformises (§7, §8); it does not describe v1's actual store layout.

**Consequences threaded through this doc:** the v1 API is partner-ready (keyed + metered, §7); the eval set has growth targets and a provenance log (§10); v2 adds a pharmacy-counter verification tool as the B2B wedge (§12); compliance artifacts (consent records, DPA chain, breach runbook, counsel sign-offs) are maintained as a diligence file, not an afterthought.

## 3. Regulatory posture (India) — read before scoping anything

> Not legal advice. This section reflects our current understanding of the two regimes that govern this product and must be reviewed by counsel **in Phase 0–1** (moved up from Phase 3 in v0.3). Both regimes are live and evolving.

### 3.1 Medical device law (CDSCO / Medical Devices Rules 2017)

Since February 2020, software with a medical purpose is regulated as a "drug"/medical device under the MDR 2017. CDSCO's October 2025 **draft** guidance on Medical Device Software distinguishes SaMD from embedded software, and classification is driven by **intended use and clinical impact, not architecture**. Software used only for **data management or general communication is not a medical device**. (The guidance is draft — monitor finalization; open Q11.)

**Our position:** a strictly non-advisory transcription + scheduling tool sits *outside* SaMD, so **no CDSCO license is required for v1.** We preserve this by rule:

- We transcribe what the doctor wrote; we never diagnose, recommend, substitute, or explain a drug.
- Intended use governs classification — and *marketing copy and in-app labelling are part of intended use*. "Your prescription says…" language applies to the Play Store listing and all promo material, not only in-app.
- The moment we add dosing advice, drug-interaction warnings, or substitution, we likely become SaMD (probably Class B, State Licensing Authority). That is a deliberate future decision, never a drift.
- **Flag, don't correct.** Strength/formulary checks may *flag* an anomaly for the user to re-verify; they must not *auto-suggest or apply* a specific corrected dose (see §6, §7). The flag UI shows an **empty input** — never a pre-filled or suggested value.

### 3.2 Data protection (DPDP Act 2023 + DPDP Rules 2025)

The DPDP Rules were notified in November 2025 with phased enforcement; the substantive obligations (consent, notice, breach reporting, data-principal rights) come fully into force ~**14 May 2027**. We have runway, but we build compliant-by-design now because retrofitting is expensive.

Consequences baked into this PRD:

- **We are a Data Fiduciary** processing health data. Accountability stays with us even where a vendor (the vision-LLM provider) processes on our behalf — that relationship needs a Data Processor contract (see §7, open Q).
- **We now hold identity-linked health data server-side (v0.4).** The prescription record is stored under a `userId`, with the phone number as the sole personal identifier. This is a larger DPDP surface than the v0.3 local-only design, and the safeguards are: **encryption of all prescription data at rest** (per-user envelope encryption under a KMS/HSM key; §8); **data minimisation** (only the phone number is retained — no name, email, address; the phone itself is stored encrypted with a keyed-HMAC blind index, never as a plaintext column); **strict, audited access control**; and **self-service delete/export by `userId`**.
- **Consent is purpose-specific and affirmative.** "Create my account and store my prescriptions to remind me", "retain per-field crops to improve accuracy", and (v2) "enable caregiver sharing" are *different purposes* → **separate opt-ins**, accuracy-retention defaulting to OFF.
- **Minors:** a parent may scan a child's prescription. DPDP requires verifiable parental consent and extra safeguards for under-18s. **Mechanics (v0.4):** when the patient is indicated as under 18, server-side persistence of the child's record is locked until a guardian completes the verification step; the scan → verify path still works locally on-device before persistence. The verification method is defined with counsel per the DPDP Rules (open Q10).
- **Security & breach:** encryption, access control, and logging are mandated; breach reporting has no materiality threshold — a **breach-reporting runbook** is a Phase 3 deliverable (§11). Storing identity-linked health data raises breach stakes, which is exactly why encryption + minimisation are non-negotiable. Penalty exposure is significant — **penalties vary by obligation, up to ~₹250 crore for security-safeguard failures** (counsel to verify current schedule; open Q10).
- **Doctor PII:** the prescription image contains the doctor's personal data (letterhead). We minimise this by retaining only per-field crops, not full images, and only on opt-in (see §8).

**Bottom line:** legal to build as a non-advisory reminder tool with DPDP-conscious data handling. The two ways it becomes regulated/risky are (a) scope creep into medical advice → CDSCO, and (b) careless health-data handling → DPDP. Server-side storage raises the stakes on (b), so encryption, minimisation, and enforceable deletion are the load-bearing controls.

## 4. Goals & non-goals

### Goals (v1)
1. Handwritten Indian prescriptions extracted at ≥85% field-level accuracy; printed/digital at ≥98% — **and near-zero undetected errors surviving verification** (the safety metric, §9).
2. Time from photo → confirmed reminders: under 90 seconds.
3. Reminders that respect meal timing with **defined AC/PC offsets** (before food = meal − 30 min; after food = meal + 30 min; user-adjustable), a **bedtime slot for HS medicines**, and auto-expiry at course end (or "ongoing" for chronic regimens).
4. **Partner-ready extraction API from day one** (image in → structured JSON out): keyed, metered, documented, versioned. v1 clients are our own app plus design-partner pilots; the same contract later serves iOS, doctor-side tools, and B2B (§2.5). Never anonymous-public.
5. **Stay outside SaMD licensing:** ship a demonstrably non-advisory product, with the verification gate and "your prescription says…" framing as the enforcing mechanisms.
6. **DPDP-ready by design:** phone-OTP accounts with minimal PII (phone only), encryption of prescription data at rest, purpose-specific opt-in consent, data minimisation, and self-service delete/export present at v1 even though full enforcement is 2027.

### Non-goals (v1)
- **No medical advice of any kind.** We transcribe what the doctor wrote; we never recommend, substitute, or explain drugs. *(This is the primary firewall keeping us a reminder tool, not a regulated medical device.)*
- **No drug-interaction warnings** (high liability, and a SaMD trigger; revisit only as a deliberate regulated-product decision post-traction).
- **No dose "correction" or substitution.** Formulary checks flag anomalies for user re-verification only; they never propose, pre-fill, or apply a specific alternative value.
- No pharmacy/ordering integration.
- No caregiver mode (v2 — the Pro headline, §12.5).
- No iOS client (backend architecture keeps it cheap to add later).
- **No personal details beyond the phone number.** Auth is phone + OTP; we store no name, email, or address. The phone number is the only identifier, held encrypted with a keyed-HMAC blind index. *(Changed in v0.4 — the v0.3 "no mandatory account / local-only storage" stance is superseded: the confirmed prescription record is now stored server-side, encrypted, keyed to a `userId`.)*
- **No default retention of prescription images.** We store the *structured, confirmed record* (drug/dose/schedule), not the image. Per-field crops are retained only on explicit opt-in, keyed to a pseudonymous retention token for the eval set (§8) — separate from the user's prescription store.
- **No non-English extraction in v1.** Scope is English/Latin-script prescriptions (including standard Latin abbreviations); other scripts route to the honest-failure path with a clear message. Regional scripts are a v2 candidate (§12).
- **No ads, ever** (§12.5 hard rule).

## 5. Target users

| Persona | Context | Key need |
|---|---|---|
| **Post-consult patient (primary)** | Urban/semi-urban Android user, just saw a GP, 3–6 medicines for 5–10 days | Zero-effort setup, doesn't miss doses, told when course ends |
| **Chronic patient** | BP/diabetes/thyroid, stable long-term regimen | Recurring daily reminders with **no end date ("ongoing" duration type, supported in v1)**; refill awareness (v2) |
| **Caregiver (v2)** | Adult child managing parent's meds remotely | Visibility into missed doses |

**Distribution hypothesis:** doctors themselves recommend the app ("scan this with RxScan, it reads my handwriting") — a zero-CAC loop unavailable to manual-entry competitors. v1 must be good enough that a doctor is comfortable vouching for it.

**Distribution ↔ liability note:** because a doctor vouching amplifies the blast radius of a mis-transcription, accuracy and honest failure modes are not polish — they are what make the doctor-recommendation loop safe. The verification gate (§6), the "I can't read this" behaviour (§6 edge cases), and the **ask-your-doctor share** (§6) exist partly to protect this loop.

## 6. User flow (v1 happy path)

1. **Sign in (account-first, v0.4.3)** — On open, the app checks for a stored session. **Persistent login:** a returning user with a valid session skips straight to step 7 (dashboard) — no re-onboarding. A new (or logged-out) user enters their phone number and verifies a **phone + OTP** code (the only personal detail we take is the phone number) before anything else happens; logging out clears the stored session and returns here.
2. **Consent** — Purpose-specific opt-ins (account/store to remind [required]; accuracy-retention [opt-in, default OFF]) captured once, post-login.
3. **Capture** — Open camera with prescription framing guide (edge detection, glare warning). Option to import from gallery/WhatsApp. **Offline capture:** if there's no connectivity, the photo is queued and extracted when the device reconnects; the user is notified when their check-list is ready.
4. **Upload & extract** — Image sent to backend. Async processing with progress state (target p50 < 8s).
5. **Verify (hard gate)** — Extraction results shown as editable cards, one per medicine. **The user must actively confirm each medicine before anything can be scheduled — this screen cannot be skipped or bulk-accepted blindly.**
   - Drug name + strength (snapped to formulary match)
   - Frequency rendered as human schedule ("Morning · Night" from 1-0-1)
   - Before/after food
   - Duration ("5 days → ends 15 July", scanned 11 July — **convention: day 1 = the scan day**; slot times already passed at scan time are simply not scheduled and shown as "added mid-day", never counted as missed/skipped). Chronic regimens use the **"ongoing — no end date"** duration type.
   - **Low-confidence fields are visually flagged and shown next to the cropped image region they came from** (the "ink chip" in the design). User taps to correct (formulary-backed autocomplete for drug names).
   - **Anomaly flags, not corrections:** if a strength isn't in the formulary or a field is unreadable, we flag "please re-check this against your prescription" with an **empty input** — we do **not** propose, pre-fill, or suggest a specific value.
   - **Ask your doctor (v0.3):** every re-check flag carries a share action that opens the phone's **native share sheet** (WhatsApp/SMS/email) with the cropped image region and a pre-written question ("Could you confirm the strength of Pantocid on my prescription from today?"). The message goes device-to-doctor; our servers never see or store it. This makes the honest-failure path actionable while remaining strictly non-advisory — the user asks *their* doctor.
   - **Mandatory confirm for non-daily frequencies:** any weekly, alternate-day, or otherwise non-daily pattern is always a low-confidence-style forced confirmation (never silently accepted), because a weekly drug misread as daily is the highest-harm extraction failure this product can make.
   - **Accessibility:** the confirmed schedule can be read aloud in descriptive form ("Augmentin 625. Your prescription says: morning and night, after food, for five days") with large-type rendering, for sick/older/low-literacy users. Read-aloud copy follows §14 (never imperative, never inferred indications).
6. **Save + schedule** — Since the session already exists (step 1), confirming the verify screen **persists the schedule server-side, encrypted, under the user's `userId`** immediately and caches it on-device for offline reminders — no login prompt at this point. The app asks for their meal times once (breakfast/lunch/dinner + **bedtime** slot for HS medicines; defaults, editable) → local notifications scheduled for every dose until course end. **AC/PC offsets:** before food fires 30 min before the meal time, after food 30 min after; both user-adjustable. **Permissions, right before the dashboard:** with the full reminder set one tap away, so the ask is legible — a one-screen primer precedes the Android 13+ **POST_NOTIFICATIONS** system dialog (the dialog tolerates ~two refusals before Android permanently silences it, so it is never triggered cold at app launch, and — since v0.4.3 — no longer tied to the sign-in moment either). **Exact-alarm access** (Android 14+, a Settings toggle via deep-link, not a dialog) is offered as a separate, skippable second step; declining falls back to WorkManager windows with an in-app accuracy note (§8). **Battery-optimization exemption** is requested contextually later (e.g., after a late-fired reminder), never stacked at onboarding. On notification denial, everything is still saved and scheduled, and Today shows a persistent "reminders are silenced" banner deep-linking to Settings. Android ≤12 auto-grants; the step is API-gated.
7. **Daily use** — Notification with actions: **Taken / Snooze 30m / Skip**. Responses logged to adherence history (device cache → synced to the server-side record). **Notification privacy:** lock-screen text is discreet by default ("Night medicines · 2 due"), never naming drugs; showing names is an explicit setting. Multiple doses at one time are grouped into a single notification.
8. **Course end** — "Your Augmentin course ends tomorrow" notification; reminders auto-stop after the last dose. From the progress view, the user can **send their adherence report to their doctor** via the native share sheet (device-to-doctor; our servers never relay it).

### Edge cases to handle in v1
- **Unreadable / low-confidence image → honest failure.** Prompt re-capture with guidance (lighting, angle) rather than shipping a confident guess. A whole-prescription or per-field confidence below threshold forces manual verification or re-shoot. Over-refusing to guess is the safer default. Non-Latin-script prescriptions get a clear "not supported yet" message, not a guess.
- Extraction succeeds partially → verify screen shows what we got; user can add a medicine manually.
- Drug not in formulary → accept free-text, flag internally for review (only if user opted into retention).
- PRN/SOS medicines → no scheduled reminder; listed with the prescription's own words ("when needed") and an optional manual dose log. We never infer or display an indication the paper doesn't state.
- Multiple prescriptions active simultaneously → merged daily schedule view; identical medicines deduplicated (see §9 adherence definition).
- **Minor's prescription** → scan/verify/local-remind works normally; server persistence of the child's record stays locked until the guardian completes verifiable consent (§3.2).
- Evening scan → only remaining slots for day 1 are scheduled (day-1-=-scan-day convention above).
- **Offline save** → if the user confirms a schedule with no connectivity, reminders schedule from the device cache immediately and the encrypted record is queued and pushed to the server on reconnect.

## 7. Extraction pipeline (the hard part)

```
Image → preprocess (deskew, crop, enhance)
      → Vision LLM with strict JSON schema + Indian Rx shorthand prompt
      → Frequency grammar parser (1-0-1, BD, TDS, QID, OD, HS, SOS, AC/PC, stat,
        weekly [e.g. once-weekly regimens], alternate-day [EOD])
      → Fuzzy match drug names against Indian formulary (~200K brand SKUs)
      → Strength anomaly FLAG against formulary ("Pantocid 45mg not found → flag for user re-check")
      → Per-field confidence scoring (non-daily frequencies always require explicit confirm)
      → Structured prescription JSON + cropped image regions per field
```

**Key decisions:**
- **No traditional OCR.** Multimodal LLMs decode prescription handwriting the way pharmacists do — using context (strength + frequency + specialty priors), not stroke recognition. Model choice determined by benchmark (see §10).
- **Formulary constraint is the biggest accuracy lever** (target design). Fuzzy-matching LLM output against a real Indian drug database converts near-misses into exact matches. Strength checks **flag** anomalies for user re-verification; they do **not** auto-correct or suggest (a correction would edge toward substitution/advice → SaMD risk, see §3.1). **v1 status:** formulary matching is **disabled** — it lived on the engine plane, which is deferred with the rest of the platform split (§7 access model, §2.5); the extraction call still returns a `formulary_id` field, it's just unpopulated until the service returns.
- **Confidence is a first-class output.** Every field carries a confidence score that drives the verification UX and the honest-failure threshold. We would rather flag 3 fields for user review than silently ship 1 wrong frequency.
- **Server-side, versioned.** Prompts, models, and formulary live on the backend; we iterate without app releases. Extractions and their eventual user corrections become our eval set and long-term moat — **but only for users who opted into retention** (§3.2, §8).
- **Data minimisation in the pipeline.** Default: discard the source image after extraction. Only per-field crops + structured JSON + corrections are retained for the eval set, and only on opt-in. The full image (which carries doctor PII on the letterhead) is not retained by default.
- **Language scope:** v1 targets English/Latin-script prescriptions; the eval set (§10) is scoped accordingly, with mixed-script samples included to validate the honest-failure path.

### Unit cost & model routing (resolves open Q2)

A scan is ~4,000 input tokens (photo ~1–2K tokens + cacheable ~2K-token system prompt) + ~1,000 output tokens. At July 2026 list prices that is **₹0.20–₹2.50 per scan** depending on tier (Gemini Flash-Lite ≈ ₹0.20 → Claude Sonnet ≈ ₹2.30; caching the system prompt shaves ~20–25%). **Production architecture: two-stage routing** — every scan goes to a Flash/Haiku-class model first; extractions with low per-field confidence escalate to a Sonnet/Pro-class second pass. At ~70/30 routing this blends to **≈ ₹1 per scan — the planning figure** until the Phase 0 bake-off produces a measured one. The Batch API discount does not apply (users wait synchronously); prompt caching does. Bake-off question, restated: *the cheapest model that clears 85% field accuracy*, per tier — the answer sets B2B margin.

### Model strategy: buy now, fine-tune later (deliberate trigger, not a v1 task)

v1 uses commercial vision LLMs — at our volumes the entire LLM bill is far below the cost of self-hosting a GPU (there is no free-tier hosting for VLM inference; a single always-on GPU instance runs ~$400–900/month, i.e. the break-even sits around ~100K scans/month *before* counting training and MLOps effort). A **fine-tuned open-weights VLM** (trained only on retention-opted-in, provenance-logged corrections — §8, §10) becomes the first-stage model when either trigger fires: (a) B2B volume makes per-scan COGS a real margin line (~10× unit-cost reduction), or (b) a partner requires on-premises / India-residency inference — which also eliminates the cross-border health-data question under DPDP, a genuine B2B differentiator. The two-stage routing above is designed so this swap changes no architecture: our model takes slot one, frontier models remain the escalation tier. The formulary stays a retrieval/constraint layer *outside* the model — it changes monthly; weights don't. The fine-tuned model then joins §2.5's asset list — noting that its value derives from the dataset and benchmark (weights depreciate as base models improve; the labeled, consented data does not).

### API contract (draft)

`POST /v1/extractions` — multipart image → `202 { extraction_id }`
`GET /v1/extractions/{id}` →
```json
{
  "status": "complete",
  "medications": [{
    "drug": { "value": "Pantocid 40mg", "formulary_id": "...", "confidence": 0.93,
              "image_region": { "x": ..., "y": ..., "w": ..., "h": ... } },
    "frequency": { "raw": "1-0-1", "slots": ["morning", "night"],
                   "pattern": "daily", "confidence": 0.99 },
    "meal_timing": { "value": "before_food", "confidence": 0.97 },
    "duration": { "type": "days", "value": 5, "confidence": 0.88 }
  }],
  "flags": ["strength for item 2 not found in formulary — user re-check"],
  "warnings": ["duration missing for item 3"]
}
```
`duration.type` ∈ `days | ongoing | unspecified`; `frequency.pattern` ∈ `daily | weekly | alternate_day | prn` (non-`daily` ⇒ forced confirm).

**Access model (v0.4.3 — single database, users-only):**

- **Extraction is stateless:** `POST /v1/extractions` runs image → parse → return. Nothing is persisted server-side pre-verification; there is no client-key / partner-key layer yet (client-key auth is a pending backend task — the app is the only caller today, and every call happens inside a logged-in session per §6 step 1, but the endpoint itself doesn't yet check that) and no formulary-match service in v1 — **formulary matching is disabled** until it's reintroduced. **No separate eval-set retention pathway exists in v1** (no corrections, no retention token).
- **The user (phone-OTP, `userId`):** the app authenticates the user via phone + OTP at sign-in (step 1, §6), receiving a session token, *before* any capture happens. The confirmed prescription record is stored under a pseudonymous `userId` via authenticated endpoints, **encrypted at rest**, in the same single database as the account/consent/preference tables. The phone number is the only PII, stored encrypted with a keyed-HMAC blind index for login lookup. Delete/export operate by `userId`.

**Deferred, not abandoned:** the v0.4 two-plane design (a client-keyed, identity-free *engine plane* for extraction/eval-set/formulary, isolated from the *consumer plane* holding `userId`) returns when the product platformises (partner API, metering, a retained eval set — §2.5). It is not v1's architecture; v1 is a single database holding only `users`, `user_consent`, `user_preference`, `prescription`, `adherence_event`.

## 8. System architecture (v1)

- **Backend:** Spring Boot service, **single PostgreSQL database** (`users`, `user_consent`, `user_preference`, `prescription`, `adherence_event`) — no separate engine-plane store in v1 (§7). Stateless extraction endpoint calls the vision LLM under a Data Processor agreement and returns the parsed result without persisting the image or an intermediate job record. Phone-OTP auth service + an **encrypted prescription store** (per-user envelope encryption under a KMS/HSM master key), holding the confirmed record keyed to `userId`; the only PII is the phone number, stored encrypted with a keyed-HMAC blind index.
- **App:** Android (Kotlin, native) — camera capture, verification UI, local notification scheduling (AlarmManager/WorkManager for exact-time delivery incl. Doze handling), **local cache of the schedule + adherence log (Room)** so reminders fire offline. The **server is the system of record**; the device cache is populated from it and queues writes (new schedules, adherence events) for push on reconnect. **Auth: phone + OTP, first (v0.4.3)** (the sole personal detail is the phone number). Sign-in runs before capture; a stored session (**persistent login**) skips straight to the dashboard on relaunch; capture/verify/save all happen inside an authenticated session, and logout clears the stored session. The account is also the rail for Pro/caregiver mode in v2 and makes delete/export trivially enforceable.
- **Notifications are local, not push** — reminders must fire without connectivity and without alarm-clock hostility (actionable notification, not alarm).
  - **Notification permission (Android 13+):** POST_NOTIFICATIONS is runtime and existential for this product; requested with a primer right before the dashboard, per §6 step 6, with the two-refusal budget respected, a denial-state banner on Today, and a high-importance "Dose reminders" notification channel created at first schedule.
  - **Named technical risk — Android 14+ exact alarms:** `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM` are restricted for non-alarm apps; a denied permission or OEM battery "optimization" silently degrades reminder timing, which defeats the product. Onboarding includes an explicit exact-alarm permission step with a plain-language explanation, plus OEM-specific battery-exemption guidance; scheduling falls back to WorkManager windows with an in-app warning if exact alarms are unavailable.
  - **Notification privacy:** discreet lock-screen text by default (count, not names); per-user setting to show names (§6).
- **Send to your doctor (v0.3):** both the flag-resolution share and the adherence-report share use the OS share sheet; content goes device-to-recipient over the user's chosen channel (WhatsApp/SMS/email). **Our servers never receive, relay, or store these messages** — this keeps the feature outside the DPDP processing surface. No doctor-side accounts in v1.
- **Privacy & data handling:**
  - Prescriptions are health data. **Purpose-specific consent:** (1) create an account and store my prescriptions to remind me [required to save]; (2) retain per-field crops + corrections to improve accuracy [separate opt-in, default OFF, pseudonymous — eval set only]; (3) enable caregiver sharing [v2].
  - **Encryption:** all prescription data is encrypted at rest under per-user envelope encryption (per-user DEK wrapped by a KMS/HSM master key); TLS in transit; the crop bucket (opt-in eval set) is private and encrypted. E2E/zero-knowledge is an open option (§13).
  - **Data minimisation:** the only personal detail retained is the phone number (encrypted + keyed-HMAC blind index). Source image discarded after extraction; the *structured* record (not the image) is what we store. Per-field crops for the eval set only on opt-in, keyed to the pseudonymous retention token (not the account).
  - **Letterhead/doctor PII:** never retained by default; if a crop is ever retained, the header band is blurred/cropped first.
  - **Self-service rights, enforceable:** in-app "delete my account/data" purges the server-side encrypted record by `userId` and wipes the device cache; the phone-number record and blind index are deleted too. Data export ships in v1 (schedule + adherence history + any retained items).
  - **Minors:** verifiable-parental-consent path when the patient is indicated as under 18; server persistence of the child's record locked until completed (§3.2).
  - No selling or sharing of health data — stated plainly, especially given the doctor-distribution strategy. **No ads, ever** (§12.5).
  - DPDP obligations reviewed with counsel starting Phase 0–1 (§11); breach-reporting runbook delivered in Phase 3.

## 9. Success metrics

| Metric | Target (v1, first 90 days) |
|---|---|
| Field-level extraction accuracy (handwritten) | ≥85%, measured against eval set |
| Field-level extraction accuracy (printed) | ≥98% |
| **Safety: confirmed-schedule audit error rate** | **~0. Audited in beta by comparing a sample of user-confirmed schedules against the source images; any undetected wrong field is a P0 investigation.** |
| Scan → confirmed schedule completion rate | ≥70% of scans |
| Median scan-to-reminders time | <90s |
| User corrections per prescription | Track — **interpret only jointly with the audit metric** (fewer corrections + zero audit errors = extraction improving; fewer corrections + audit errors = rubber-stamping, a UX failure) |
| D7 retention among users with an active course | ≥40% |
| **D30 / next-prescription loyalty** | Track % of users who scan their *next* prescription — the true loyalty signal for 5–10-day courses |
| Doses marked "Taken" / doses due | ≥60%. **Definition:** denominator = scheduled doses that reached their fire time (excludes "added mid-day" slots and PRN); snooze-then-taken counts as taken; identical medicines across merged prescriptions deduplicate to one dose. |
| Retention opt-in rate | Track (feeds eval-set growth; healthy consent UX signal) |

**North star:** confirmed schedules per week (captures both acquisition and extraction quality).

## 10. Pre-development: model bake-off (Week 0)

Before app code, build the eval harness:
1. Collect **50+ real handwritten Indian prescriptions** (family, friends, own files) with hand-labeled ground truth per field. **Data-handling rules for the bake-off (v0.3):** written participant consent documented per prescription (this covers the *doctor's* PII too — the letterhead is their personal data); letterheads and patient names stripped/cropped from images *before* they leave our machines; providers called with zero-retention/no-training API settings; a provenance log (who consented, when, for what) kept from day one — this log is part of the diligence file (§2.5).
2. Benchmark 3–4 vision models (Gemini, Claude, GPT-4o class) with the same schema prompt.
3. Measure field-level accuracy per field type; pick the model and set the v1 accuracy baseline.
4. Keep growing this set forever — **500 labeled prescriptions by launch; 2,000+ across regions/specialties by end of year one.** This eval set is the defensible asset; the models are commodities. Post-launch growth comes only from retention opt-ins, inheriting the same provenance log.

## 11. Milestones

| Phase | Scope | Est. |
|---|---|---|
| **0. Bake-off + counsel kickoff** | Eval set (50 Rx, per §10 data rules), model benchmark, go/no-go on handwriting accuracy. **Counsel engaged now: written intended-use statement + CDSCO non-device posture sign-off + DPDP consent-flow review** (moved up from Phase 3) | 1–2 wks |
| **1. Extraction API + consumer backend** | Spring Boot service, **single Postgres database**; stateless extraction endpoint (image → parse → return, no server-side persistence, no formulary matching in v1 — deferred with the engine plane, §2.5/§7), Data Processor agreement with LLM provider (Claude by default; pluggable); **phone-OTP auth service; encrypted prescription store (envelope encryption + KMS/HSM); delete/export by userId** | 4 wks |
| **2. Android core** | **Phone-OTP sign-in (first, with persistent login)** → consent → capture (incl. offline queue) → verify (hard gate, flag inputs, ask-your-doctor share) → schedule (AC/PC offsets, bedtime slot, ongoing durations) → notify (exact-alarm onboarding, discreet lock screen) → adherence log; **server-backed store with on-device cache for offline firing**; purpose-specific consent; delete/export | 4 wks |
| **3. Polish & safety** | Edge cases, disclaimers, minor-consent path, **breach-reporting runbook**, final counsel review of listing copy, onboarding, Play Store listing (non-advisory copy) | 2 wks |
| **4. Beta** | 20–30 real users (start with friendly doctors' patients), measure metrics in §9 **including the confirmed-schedule audit** | 3 wks |

## 12. v2 candidates (explicitly out of v1)
- Caregiver mode (missed-dose alerts to family) — **the Pro headline feature (§12.5)**; builds on the phone-OTP account + server-side store (already in v1), so v2 adds multi-profile + sharing, not the account itself.
- Refill reminders for chronic regimens.
- iOS client (backend already supports it).
- Doctor-side distribution kit (clinic QR code, "verified for Dr. X's handwriting").
- B2B extraction API partnerships at scale — **platform asset (§2.5)**; the v1 API is already keyed/metered/partner-ready, so v2 is sales + SLAs, not re-architecture.
- **Pharmacy-counter verification tool:** the B2B wedge — a pharmacist, not the patient, confirms the extraction at the counter. This is the workflow e-pharmacies currently staff with human verifiers.
- Regional language prescriptions and UI (the design system's type choices — e.g. Devanagari-ready faces — anticipate this).
- *Anything advisory* (interaction warnings, dosing guidance) — only as a deliberate, separately-scoped **regulated** SaMD product with CDSCO licensing, never a v1 drift.

## 12.5 Business model

**Hard rules (peers of "no selling health data", §8):**
- **No ads. Ever.** Not even untargeted banners. Ad inventory next to a prescription reads as monetized health data regardless of targeting; prescription-drug advertising to the public is restricted in India (Drugs & Magic Remedies Act) and pharma is the likeliest bidder on this placement; ad revenue is negligible against the trust cost to the doctor loop and the diligence cost to the platform thesis (§2.5).
- **The safety core is always free.** Scan → verify → remind, for any prescription, with **no limit on the number of medicines or reminders**. The user with 11+ medicines is the elderly/chronic patient at highest risk — the exact person doctors recommend the app for. We never paywall their next medicine. Pro sells convenience and family features, never safety. **The ask-your-doctor share and the basic send-report share are part of the safety core and stay free** (they are the actionable form of "when in doubt, ask your doctor").

**Free tier:** unlimited medicines and reminders for active regimens; scanning with a generous monthly cap (set so ordinary patients and families never hit it; it exists only because each scan has real LLM cost and to stop counter-scale abuse — e.g. 10–15 scans/month, tune with data; open Q8); meal-timed schedules with AC/PC offsets; course auto-stop; adherence history; ask-your-doctor share + basic send-to-doctor report; delete/export.

**RxScan Pro (consumer, ~₹99–149/month or ~₹699/year, family-priced):**
- **Caregiver mode** — missed-dose alerts to family, multi-profile (parents + children). The headline feature; targets the highest-willingness-to-pay segment (adult children managing parents' medication). Builds on the phone-OTP account + server-side store (§8).
- Backup & sync across devices (the phone-OTP account + server-side store makes this native).
- Refill reminders for chronic regimens.
- **Formatted multi-week adherence reports** (trends, per-medicine breakdowns) — the *basic* single-course report share stays free per the safety-core rule.
- Extended history retention (per consent).

**B2B (the platform revenue, §2.5):** metered per-extraction API pricing for pharmacies, health apps, hospital OPD software; pharmacy-counter verification tool (v2 wedge); doctor/clinic kits. B2B pricing to be set after unit cost per scan is known (open Q2).

**Sequencing:** v1 free and generous to win the doctor loop → Pro (caregiver/family) with v2 → B2B API at scale once the eval set proves accuracy publicly. Monetization never touches the scan-to-verify-to-remind core that doctors vouch for.

## 13. Open questions
1. Working name + Play Store identity. "RxScan" is a placeholder. (Listing copy must stay non-advisory — see §3.1.)
2. ~~Extraction cost per scan at target model?~~ **Resolved (§7):** ≈ ₹1/scan planning figure via two-stage routing (Flash-class first, escalate low-confidence to Sonnet/Pro-class); Phase 0 bake-off produces the measured number per model tier. B2B pricing anchors at ₹5–15/extraction against this COGS.
3. ~~Consent flow: opt-in vs opt-out?~~ **Resolved:** purpose-specific; account/store to remind [required to save], accuracy-retention opt-in default OFF (§3.2, §8).
4. ~~Doctor PII on letterhead in retained images?~~ **Resolved:** don't retain full images by default; retain per-field crops only, on opt-in; blur header if ever needed (§8).
5. ~~Account required in v1?~~ **Resolved (v0.4): yes.** Phone-OTP account; the confirmed prescription record is stored server-side, encrypted, keyed to a `userId`; the only PII retained is the phone number. **Superseded by Q13 (v0.4.3):** sign-in now runs *before* scan/verify, not after — see Q13 below.
6. Who owns which workstream — suggest one on extraction API + eval harness + consumer backend, one on Android.
7. Does our chosen vision-LLM provider's terms permit sending Indian health data, and with what retention? Need a Data Processor agreement, and watch pending DPDP cross-border-transfer notifications.
8. Free-tier scan cap — pick the number from beta data so that ~99% of genuine patients never hit it.
9. Pro pricing test (₹99–149/mo vs ₹699/yr anchor) and whether family profiles are bundled or add-ons.
10. **Counsel (Phase 0–1):** written intended-use statement; verify the DPDP penalty schedule wording in §3.2; define the verifiable-parental-consent method per DPDP Rules; review the phone-OTP + encrypted-storage data-handling posture.
11. **Monitor:** finalization of CDSCO's draft MDSW guidance — re-check our non-device posture against the final text.
12. ~~**Encryption model:** envelope vs end-to-end?~~ **Resolved: envelope encryption (server-decryptable).** Per-user DEK wrapped by a KMS/HSM master key. Chosen because it (a) recovers cleanly when a user loses their phone — they re-login via phone-OTP and the server restores the record, whereas E2E/zero-knowledge *cannot* recover a lost-device key without a separate escrow scheme; and (b) is legally sufficient — DPDP Rules 2025 (Rule 6) require "reasonable security safeguards" (encryption at rest + in transit, access control, logging, breach response, DP agreements), **not** zero-knowledge/E2E, and DPDP has no special-category mandate for health data. Legal defensibility comes from key management (HSM master key, no standing decrypt access, audit logs, breach runbook — §8), not from making encryption irreversible. Counsel to confirm in the Phase 0–1 review (Q10).
13. ~~**Account mandatory before scan, or only at save?**~~ **Reopened and re-resolved (v0.4.3): before scan — account-first.** Phone-OTP sign-in is now the *first* step (§6 step 1); scan, verify, save, and schedule all happen inside an authenticated session, with no separate login prompt at save. **Persistent login:** a returning user with a valid session skips sign-in entirely and lands on the dashboard; logout clears the session. **Why the reversal from v0.4.1's "at save":** the v0.4.1 rationale (ask lands after the extraction "proof", pre-login scans need device-level metering, honest-failure users leave no orphan phone numbers) optimized for first-scan conversion at the cost of re-running onboarding on every cold start and needing a separate pre-login-metering + unauthenticated-verify-state code path. v0.4.3 trades a small amount of upfront friction (phone number asked before the user has seen the app work) for a materially simpler client (one auth state, no device-attestation metering, no deferred-save reconciliation) and a session that survives app restarts — judged the better tradeoff now that the product is moving from prototype to a real backend. Pre-login scans and device-metering are dropped; every extraction call now runs inside a user session.

## 14. What we deliberately do not say to users
- Never "you should take…" — always "your prescription says…". This governs read-aloud copy too.
- Never interpret, substitute, or explain drugs — including never displaying an inferred indication ("for fever") or a descriptive pseudo-generic ("cough syrup") that the paper doesn't state.
- Never propose, pre-fill, or suggest a corrected value — only flag "please re-check this against your prescription" with an empty input.
- Prominent disclaimer at verification: *"Please confirm against your prescription. When in doubt, ask your doctor or pharmacist."* The **ask-your-doctor share** (§6) is the actionable form of this sentence.
- The same non-advisory language governs marketing and the Play Store listing, not just the app.

---

## Annex A — v0.2 audit: resolution log

All 21 anomalies from the v0.2 review are resolved in this version as follows (numbering per the review). Any of these can still be vetoed — flag the number and the body text gets reverted.

| # | Anomaly | Resolution (where) |
|---|---|---|
| 1 | Anonymous retention vs delete/export rights | Pseudonymous retention token for the eval set (§7, §8); the user's own record is deleted/exported by `userId` (§8) |
| 2 | Public API vs B2B non-goal | Keyed, metered, partner-ready API; never anonymous-public (§2.5, §4, §7) |
| 3 | Chronic persona vs course-centric flow | "Ongoing — no end date" duration type in v1 (§5, §6, §7 schema) |
| 4 | End-date convention inconsistent | Day 1 = scan day; passed slots = "added mid-day", never "missed" (§6) |
| 5 | No safety metric | Confirmed-schedule audit error rate, P0 on any miss (§9, §11 beta) |
| 6 | AC/PC offsets undefined | −30/+30 min defaults, user-adjustable (§4, §6) |
| 7 | HS has no slot | Fourth, meal-independent bedtime slot (§6) |
| 8 | Notification privacy | Discreet lock screen by default; names opt-in; grouped notifs (§6, §8) |
| 9 | Weekly/alternate-day dosing missing | Grammar extended; non-daily ⇒ mandatory confirm (§6, §7) |
| 10 | Language scope unstated | v1 = English/Latin script; others → honest failure (§4, §6, §7) |
| 11 | Android 14+ exact alarms | Named risk; permission onboarding + fallback + warning (§8) |
| 12 | No offline capture | Photo queued, extracted on reconnect (§6, §8) |
| 13 | No breach runbook milestone | Phase 3 deliverable (§3.2, §11) |
| 14 | Minor-consent mechanics | Local scan/verify works; server persistence locked until guardian verifies (§3.2, §6, §8) |
| 15 | Counsel too late | Moved to Phase 0–1 (§3, §11) |
| 16 | Bake-off ships health data | Strip PII first, zero-retention API settings, documented consent + provenance log (§10) |
| 17 | "Declining corrections" ambiguous | Interpreted only jointly with the audit metric (§9) |
| 18 | Penalty figure | "Varies by obligation, up to ~₹250 crore"; counsel to verify (§3.2, Q10) |
| 19 | CDSCO guidance is draft | Marked draft; monitor finalization (§3.1, Q11) |
| 20 | D7 measures little | D30 / next-prescription loyalty metric added (§9) |
| 21 | Adherence denominator undefined | Defined: due doses only, snooze-then-taken = taken, dedupe merged meds (§9) |
