package com.rxscan.backend.extraction.parse;

/**
 * Why a field needs a human re-check. A reason names the problem; it never proposes a value
 * (CDSCO "flag, don't correct"). See the flag-policy table in the parser spec §Components 5.
 */
public enum FlagReason {
    FREQ_UNRECOGNIZED,
    FREQ_NON_DAILY,
    STRENGTH_ANOMALY,
    STRENGTH_UNREADABLE,
    NAME_LOW_CONFIDENCE,
    DURATION_UNCLEAR,
    FIELD_LOW_CONFIDENCE
}
