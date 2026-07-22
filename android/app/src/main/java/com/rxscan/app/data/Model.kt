package com.rxscan.app.data

// Domain model mirroring the design prototype's med shape (RxScan-v2-design-v3.html)
// and the extraction schema in rxscan-tech-design-v0_2_3.md §4.
// CDSCO invariant: a flag names the field and asks for a re-check — there is NO
// field anywhere for a system-suggested value. The user types what the paper says.

// STRENGTH/DURATION have bespoke inputs (text ≥2 chars; numeric 1–60 days).
// OTHER is the generic re-check box for any other flagged field (frequency, name,
// meal…) the backend can raise — free-text, non-empty, still forces a confirm and
// never carries a suggested value. Critically it means a high-harm flag like
// non-daily frequency is never silently dropped (CLAUDE.md invariant).
enum class FlagKind { STRENGTH, DURATION, OTHER }

/** A re-check flag: title + plain-language body + an EMPTY input. Never a suggestion. */
data class ReCheckFlag(
    val kind: FlagKind,
    val title: String,
    val body: String,
    val placeholder: String,
)

data class Medication(
    val id: String,
    val name: String,
    val strength: String?,     // null ⇒ unreadable (flag of kind STRENGTH)
    val ink: String,           // what the handwriting literally says
    val schedule: String,      // "Morning · Night"
    val food: String,          // "After food"
    val duration: String?,     // null ⇒ unclear (flag of kind DURATION)
    val aloud: String,         // read-aloud copy — §14: "your prescription says…"
    val prn: Boolean = false,  // SOS/when-needed: listed, never scheduled
    val flag: ReCheckFlag? = null,
)
