package com.rxscan.backend.extraction.parse;

/**
 * Frequency pattern. Anything other than {@link #DAILY} forces a confirm in Verify — a weekly
 * or alternate-day medicine misread as daily is the product's highest-harm error (CLAUDE.md).
 */
public enum Pattern {
    DAILY,
    WEEKLY,
    ALTERNATE_DAY,
    PRN,
    STAT
}
