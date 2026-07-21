package com.rxscan.backend.extraction.parse;

import java.util.List;

/**
 * The structured, confidence-scored, flag-annotated result for one medicine — the single source of
 * truth the Verify screen renders. Mirrors tech-design §4. Displayed {@code value}s are always what
 * was read; a formulary match adds a {@code formularyId} but never rewrites the text.
 */
public record MedParseResult(Drug drug, Strength strength, Frequency frequency,
                             MealTiming mealTiming, Duration duration, List<Flag> flags) {

    /** Name as read + resolved catalog id (nullable) + blended confidence. */
    public record Drug(String value, Long formularyId, double confidence) {
    }

    /** Strength as read (never corrected) + confidence. */
    public record Strength(String value, double confidence) {
    }

    /** Raw notation + parsed schedule + pattern + confidence. */
    public record Frequency(String raw, Slots slots, Pattern pattern, double confidence) {
    }

    /** Meal relation (nullable — never inferred) + confidence. */
    public record MealTiming(Meal value, double confidence) {
    }

    /** Course length + confidence. */
    public record Duration(DurationType type, Integer days, double confidence) {
    }
}
