package com.rxscan.app.data

// The design prototype's exact demo prescription (RxScan-v2-design-v3.html `meds`):
//  - Augmentin: clean, no flag
//  - Pantocid: strength unreadable → re-check flag (empty input, we never guess doses)
//  - Ascoril LS: duration unclear → re-check flag (numeric days)
//  - Dolo 650: PRN/SOS — listed in the paper's own words, never scheduled

object MockData {

    val prescription: List<Medication> = listOf(
        Medication(
            id = "m1",
            name = "Augmentin 625 Duo",
            strength = "625 mg",
            ink = "Augmentin 625 — 1-0-1 ×5d (a/f)",
            schedule = "Morning · Night",
            food = "After food",
            duration = "5 days · ends Wed, 15 Jul",
            aloud = "Augmentin 625. Your prescription says: morning and night, after food, for 5 days.",
        ),
        Medication(
            id = "m2",
            name = "Pantocid",
            strength = null,
            ink = "Pantocid 4? — 1-0-0 (b/f)",
            schedule = "Morning",
            food = "Before food",
            duration = "5 days · ends Wed, 15 Jul",
            aloud = "Pantocid. Your prescription says: morning, before food, for 5 days. Please check the strength on your paper.",
            flag = ReCheckFlag(
                kind = FlagKind.STRENGTH,
                title = "We couldn’t read the strength",
                body = "Type exactly what’s written on your paper. If you can’t read it either, ask your pharmacist or doctor — we never guess doses.",
                placeholder = "e.g. what the paper says",
            ),
        ),
        Medication(
            id = "m3",
            name = "Ascoril LS",
            strength = "2.5 ml",
            ink = "Ascoril LS — 1-1-1",
            schedule = "Morning · Afternoon · Night",
            food = "After food",
            duration = null,
            aloud = "Ascoril L S. Your prescription says: morning, afternoon and night, after food. Please check the number of days on your paper.",
            flag = ReCheckFlag(
                kind = FlagKind.DURATION,
                title = "How many days?",
                body = "The number of days wasn’t clear. If it’s written on your paper, enter it. If it isn’t, ask your doctor before setting one.",
                placeholder = "days",
            ),
        ),
        Medication(
            id = "m4",
            name = "Dolo 650",
            strength = "650 mg",
            ink = "Tab Dolo 650 — SOS",
            schedule = "When needed (SOS)",
            food = "As written",
            duration = "No fixed course",
            aloud = "Dolo 650. Your prescription says: when needed. No reminders will be scheduled for this one.",
            prn = true,
        ),
    )
}
