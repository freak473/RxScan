package com.rxscan.backend.extraction.parse;

/**
 * One medicine as the vision model read it — every field verbatim, exactly as printed/written.
 * This is the parser's input contract; supplied as fixtures today, produced by the single vision
 * call later (tech-design §2.3). {@code imageRegion}/crop coords are out of scope for this layer.
 */
public record VisionMedRaw(String name, String strength, String doseNotation, String duration,
                           String meal, FieldConfidence confidence) {
}
