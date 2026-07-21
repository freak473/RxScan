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

    private static final String EMPTY = "";
    private static final java.util.regex.Pattern NON_ALNUM = java.util.regex.Pattern.compile("[^a-z0-9]");

    // Recognised meal-note keys, in compact form (lowercased, non-alphanumerics stripped).
    private static final String AC = "ac";
    private static final String BEFORE_FOOD = "beforefood";
    private static final String EMPTY_STOMACH = "emptystomach";
    private static final String PC = "pc";
    private static final String AFTER_FOOD = "afterfood";
    private static final String WITH_FOOD = "withfood";

    // Keyed by compact form so "before food" and "beforefood" both hit. No entry is ever
    // inferred — only these explicit notes map.
    private static final Map<String, Meal> MEALS = Map.ofEntries(
            Map.entry(AC, Meal.BEFORE),
            Map.entry(BEFORE_FOOD, Meal.BEFORE),
            Map.entry(EMPTY_STOMACH, Meal.BEFORE),
            Map.entry(PC, Meal.AFTER),
            Map.entry(AFTER_FOOD, Meal.AFTER),
            Map.entry(WITH_FOOD, Meal.WITH));

    /**
     * @return the recognized {@link Meal}, or {@code null} when {@code meal} is null, blank, or
     *         not one of the known notes. Case-insensitive.
     */
    public static Meal parse(String meal) {
        if (meal == null || meal.isBlank()) {
            return null;
        }
        String compact = NON_ALNUM.matcher(meal.toLowerCase()).replaceAll(EMPTY);
        return MEALS.get(compact);
    }
}
