package com.rxscan.backend.extraction.parse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic course-duration parser. Pure and model-agnostic — see
 * docs/superpowers/specs/2026-07-20-rx-deterministic-parser-design.md §Components 4.
 *
 * <p>Recognizes explicit day/week counts ({@code "5 days"}, {@code "x5d"}, {@code "5/7"},
 * {@code "1 week"}, {@code "2 weeks"}) and open-ended words ({@code "continue"}, {@code "ongoing"},
 * {@code "regular"}). Day counts are bounds-checked 1..60 (the Android numeric flag validator's
 * range); anything absent, unparseable, or out of range → {@link DurationType#UNSPECIFIED}. Nothing
 * is ever inferred.
 */
public final class DurationParser {

    private DurationParser() {
    }

    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 60;

    private static final Pattern ONGOING = Pattern.compile("\\b(continue|ongoing|regular)\\b");
    // "5/7" prescriber shorthand: n days out of a 7-day week.
    private static final Pattern SLASH_7 = Pattern.compile("(\\d+)\\s*/\\s*7\\b");
    // Weeks: optional leading "x", a count, then a week unit. Checked before days so "1w" ≠ days.
    private static final Pattern WEEKS = Pattern.compile("x?\\s*(\\d+)\\s*(?:weeks|week|wks|wk|w)\\b");
    // Days: optional leading "x", a count, then a day unit.
    private static final Pattern DAYS = Pattern.compile("x?\\s*(\\d+)\\s*(?:days|day|d)\\b");

    /**
     * @return a {@link DurationResult}; {@code UNSPECIFIED} when {@code duration} is null, blank,
     *         unparseable, or a day count outside 1..60. Case-insensitive.
     */
    public static DurationResult parse(String duration) {
        if (duration == null || duration.isBlank()) {
            return DurationResult.UNSPECIFIED;
        }
        String lower = duration.trim().toLowerCase();

        if (ONGOING.matcher(lower).find()) {
            return DurationResult.ONGOING;
        }
        Matcher slash = SLASH_7.matcher(lower);
        if (slash.find()) {
            return boundedDays(Integer.parseInt(slash.group(1)));
        }
        Matcher weeks = WEEKS.matcher(lower);
        if (weeks.find()) {
            return boundedDays(Integer.parseInt(weeks.group(1)) * 7);
        }
        Matcher days = DAYS.matcher(lower);
        if (days.find()) {
            return boundedDays(Integer.parseInt(days.group(1)));
        }
        return DurationResult.UNSPECIFIED;
    }

    private static DurationResult boundedDays(int days) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            return DurationResult.UNSPECIFIED;
        }
        return DurationResult.ofDays(days);
    }
}
