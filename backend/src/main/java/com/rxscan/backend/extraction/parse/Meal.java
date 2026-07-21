package com.rxscan.backend.extraction.parse;

/**
 * When a dose is taken relative to food. Populated only from what the vision model read
 * (parser spec §Output); {@code null} when absent — never inferred.
 */
public enum Meal {
    BEFORE,
    AFTER,
    WITH
}
