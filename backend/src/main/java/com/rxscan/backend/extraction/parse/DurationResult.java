package com.rxscan.backend.extraction.parse;

/**
 * Outcome of parsing a course duration.
 *
 * @param type the kind of duration read
 * @param days concrete day count, non-null only when {@code type == DAYS}
 */
public record DurationResult(DurationType type, Integer days) {

    static final DurationResult UNSPECIFIED = new DurationResult(DurationType.UNSPECIFIED, null);
    static final DurationResult ONGOING = new DurationResult(DurationType.ONGOING, null);

    static DurationResult ofDays(int days) {
        return new DurationResult(DurationType.DAYS, days);
    }
}
