package com.rxscan.backend.extraction.parse;

import java.util.Map;

/**
 * Deterministic meal-timing parser: maps the vision model's as-written meal note to a {@link Meal}.
 * Pure and model-agnostic — see
 * docs/superpowers/specs/2026-07-20-rx-deterministic-parser-design.md §Components 3.
 *
 * <p>Meal timing is <strong>never inferred</strong> from dose notation (CLAUDE.md "No inferred
 * fields"): {@code AC}/before food/empty stomach → {@link Meal#BEFORE}; {@code PC}/after food →
 * {@link Meal#AFTER}; with food → {@link Meal#WITH}; anything else or {@code null} → {@code null}.
 */
public final class MealTimingParser {

    private MealTimingParser() {
    }

    // Keyed by compact form (lowercased, non-alphanumerics stripped) so "before food" and
    // "beforefood" both hit. No entry is ever inferred — only these explicit notes map.
    private static final Map<String, Meal> MEALS = Map.ofEntries(
            Map.entry("ac", Meal.BEFORE),
            Map.entry("beforefood", Meal.BEFORE),
            Map.entry("emptystomach", Meal.BEFORE),
            Map.entry("pc", Meal.AFTER),
            Map.entry("afterfood", Meal.AFTER),
            Map.entry("withfood", Meal.WITH));

    /**
     * @return the recognized {@link Meal}, or {@code null} when {@code meal} is null, blank, or
     *         not one of the known notes. Case-insensitive.
     */
    public static Meal parse(String meal) {
        if (meal == null || meal.isBlank()) {
            return null;
        }
        String compact = meal.toLowerCase().replaceAll("[^a-z0-9]", "");
        return MEALS.get(compact);
    }
}
