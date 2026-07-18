# Product

## Register

product

## Platform

android

## Users

Post-consult patients in urban/semi-urban India — Android users who just left a GP with a handwritten prescription for 3–6 medicines, often sick, stressed, sometimes older or low-literacy. Secondary: chronic patients (BP/diabetes/thyroid) on ongoing regimens with no end date. They are standing outside a clinic and will not type six medicines into a form; the job is zero-effort setup that never lets them silently take the wrong dose. Doctors are a distribution channel ("scan this with RxScan") — the app must be good enough for a doctor to vouch for.

## Product Purpose

Scan a handwritten prescription → verified medication reminders. The product is not OCR and not reminders; it is an **extraction + verification** system whose UX makes the remaining extraction errors impossible to silently get wrong. It is a *scribe, not an advisor*: it records what the doctor wrote and never diagnoses, recommends, or interprets — this framing is what keeps it outside India's SaMD/CDSCO regime. Ships Android-first (Kotlin + Jetpack Compose); an iOS port is intended eventually, so avoid gratuitous Android-isms where a neutral choice works equally well. Success = a patient completes scan → verify → schedule in under a minute and finishes their course.

## Positioning

The only reminder app a sick person can set up without typing: photograph the prescription, confirm what it says, done.

## Brand Personality

Friendly and encouraging — a warm companion, not a clinical instrument. It celebrates showing up ("3 of 4 taken today") without guilt-tripping misses. Warmth lives inside hard rails: the app quotes the paper ("your prescription says…"), never speaks with medical authority ("you should take…"), and is honest when it can't read something rather than confidently guessing. Trust is earned through that honesty plus radical ease of use, not through medical gravitas.

## Anti-references

Not the blue/white gradient sameness of generic healthtech (1mg, Practo, PharmEasy templates). Not hospital/enterprise software — dense clinical dashboards and form-heavy flows intimidate exactly the older and low-literacy users this serves. Every screen should feel effortless enough for someone's worst day.

## Design Principles

- **Verification is the hero, not friction.** The confirm gate is the product's core promise; design it as the moment of reassurance, never a hurdle to skip past.
- **Flag, don't correct.** An anomalous field gets an empty input and "please re-check against your prescription" — never a pre-filled or suggested value. This is a legal invariant, enforced in code.
- **Honest failure beats confident guessing.** Unreadable image → guided re-capture; unreadable field → flag + ask-your-doctor share. Over-refusing is the safer default.
- **Easy for the worst day.** Designed for a sick person outside a clinic: big targets, plain words, one thing per screen, nothing to type that a photo can provide.
- **Encouraging, never nagging.** Progress framed as support, not scorekeeping; misses are recorded, not scolded.

## Accessibility & Inclusion

WCAG 2.1 AA: contrast ≥ 4.5:1, 48 dp touch targets, TalkBack support, sp-based type that follows system font size. The PRD additionally commits to read-aloud schedules in descriptive form and large-type rendering for sick/older/low-literacy users; read-aloud copy follows the same non-advisory language rules. Notifications are discreet by default (never naming drugs on the lock screen).
