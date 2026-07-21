package com.rxscan.backend.extraction.parse;

/**
 * Per-field confidence (0.0..1.0) the vision model reported for one medicine's raw reads. Kept
 * separate from the text so the deterministic layer can weigh each field independently.
 */
public record FieldConfidence(double name, double strength, double doseNotation,
                              double duration, double meal) {
}
