package com.rxscan.backend.extraction.parse;

/**
 * Tunable numeric thresholds for the deterministic parser, gathered in one place. All values are
 * provisional pending the §9.5 confidence-threshold study (parser spec §Components 5, Open
 * questions) — they will be re-fit against a labeled eval set later, so they live here rather than
 * being scattered as magic numbers.
 */
public final class ParserThresholds {

    private ParserThresholds() {
    }

    /** Trigram score at/above which a formulary SKU is resolved and its {@code formularyId} set. */
    public static final double MATCH_HIGH = 0.55;

    /** Trigram score below which there is no usable candidate at all. */
    public static final double MATCH_LOW = 0.30;

    /** Model per-field confidence below which a field is treated as unreliable (raises a flag). */
    public static final double CONF_MIN = 0.60;

    /** Confidence ceiling for an inherently ambiguous frequency (e.g. 2-slot "1-1" shorthand). */
    public static final double AMBIGUOUS_FREQ_CAP = 0.50;
}
