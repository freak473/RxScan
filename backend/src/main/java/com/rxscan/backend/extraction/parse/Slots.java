package com.rxscan.backend.extraction.parse;

/**
 * Dose count per time-of-day slot. Counts are fractional to preserve half-tablet doses
 * ("½-0-½"). Bedtime (HS) is a distinct slot from night, per tech-design §2.1's bedtime offset.
 */
public record Slots(double morning, double noon, double night, double bedtime) {

    static final Slots NONE = new Slots(0, 0, 0, 0);
}
