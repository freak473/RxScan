package com.rxscan.app.data

// Domain model mirroring the design prototype's med shape (RxScan-v2-design-v3.html)
// and the extraction schema in rxscan-tech-design-v0_2_2.md §4.
// CDSCO invariant: a flag names the field and asks for a re-check — there is NO
// field anywhere for a system-suggested value. The user types what the paper says.

enum class FlagKind { STRENGTH, DURATION }

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
