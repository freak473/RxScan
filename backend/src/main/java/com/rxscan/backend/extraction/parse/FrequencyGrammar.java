package com.rxscan.backend.extraction.parse;

import java.util.Map;

/**
 * Deterministic dose-notation grammar: turns what the vision model read ("1-0-1", "BD", "SOS",
 * "weekly") into a structured schedule. Pure and model-agnostic — see
 * docs/superpowers/specs/2026-07-20-rx-deterministic-parser-design.md §Components 1.
 *
 * <p>This is the single most safety-critical unit (weekly-misread-as-daily), so it is exhaustively
 * table-tested and never guesses: an unrecognized notation returns {@code recognized=false} and the
 * orchestrator raises an empty re-check flag rather than inventing a schedule.
 */
public final class FrequencyGrammar {

    private FrequencyGrammar() {
    }

    private static final String HYPHEN = "-";
    private static final String EMPTY = "";

    // Fraction glyphs and their decimal values (a half / quarter / three-quarter tablet).
    private static final String HALF = "½";
    private static final String QUARTER = "¼";
    private static final String THREE_QUARTER = "¾";

    // A positional notation is number-or-fraction tokens joined by hyphens:
    // "1-0-1", "0.5-0-0.5", "½-0-½", "1-0-0-1".
    private static final String NUM = "(?:\\d*\\.?\\d+|[" + HALF + QUARTER + THREE_QUARTER + "])";
    private static final java.util.regex.Pattern POSITIONAL =
            java.util.regex.Pattern.compile(NUM + "(?:" + HYPHEN + NUM + ")+");
    // Unicode dash variants (hyphen/figure/en/em dash) normalised to an ASCII hyphen before split.
    private static final java.util.regex.Pattern DASH_VARIANTS =
            java.util.regex.Pattern.compile("[‐-―]");
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");
    private static final java.util.regex.Pattern NON_ALNUM = java.util.regex.Pattern.compile("[^a-z0-9]");

    // Abbreviations / phrases, keyed by their compact form (lowercased, alphanumerics only).
    private static final Map<String, FrequencyResult> ABBREV = Map.ofEntries(
            Map.entry("od", daily(1, 0, 0, 0)),
            Map.entry("oncedaily", daily(1, 0, 0, 0)),
            Map.entry("bd", daily(1, 0, 1, 0)),
            Map.entry("bid", daily(1, 0, 1, 0)),
            Map.entry("twicedaily", daily(1, 0, 1, 0)),
            Map.entry("tds", daily(1, 1, 1, 0)),
            Map.entry("tid", daily(1, 1, 1, 0)),
            Map.entry("thricedaily", daily(1, 1, 1, 0)),
            Map.entry("qid", daily(1, 1, 1, 1)),
            Map.entry("qds", daily(1, 1, 1, 1)),
            Map.entry("hs", daily(0, 0, 0, 1)),
            Map.entry("atbedtime", daily(0, 0, 0, 1)),
            Map.entry("sos", pattern(Pattern.PRN)),
            Map.entry("prn", pattern(Pattern.PRN)),
            Map.entry("asneeded", pattern(Pattern.PRN)),
            Map.entry("stat", pattern(Pattern.STAT)),
            Map.entry("immediately", pattern(Pattern.STAT)),
            Map.entry("weekly", pattern(Pattern.WEEKLY)),
            Map.entry("onceaweek", pattern(Pattern.WEEKLY)),
            Map.entry("ow", pattern(Pattern.WEEKLY)),
            Map.entry("eod", pattern(Pattern.ALTERNATE_DAY)),
            Map.entry("alternateday", pattern(Pattern.ALTERNATE_DAY)),
            Map.entry("altday", pattern(Pattern.ALTERNATE_DAY)),
            Map.entry("everyotherday", pattern(Pattern.ALTERNATE_DAY)));

    public static FrequencyResult parse(String doseNotation) {
        if (doseNotation == null || doseNotation.isBlank()) {
            return FrequencyResult.unrecognized();
        }
        String lower = doseNotation.trim().toLowerCase();

        // Positional first (numbers-and-hyphens). Normalise en/em dashes and drop inner spaces.
        String dashed = DASH_VARIANTS.matcher(lower).replaceAll(HYPHEN);
        String positional = WHITESPACE.matcher(dashed).replaceAll(EMPTY);
        if (POSITIONAL.matcher(positional).matches()) {
            FrequencyResult r = parsePositional(positional);
            if (r != null) {
                return r;
            }
        }

        // Otherwise an abbreviation / phrase, matched on its compact form.
        String compact = NON_ALNUM.matcher(lower).replaceAll(EMPTY);
        FrequencyResult abbrev = ABBREV.get(compact);
        return abbrev != null ? abbrev : FrequencyResult.unrecognized();
    }

    private static FrequencyResult parsePositional(String s) {
        String[] parts = s.split(HYPHEN);
        double[] v = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Double d = fraction(parts[i]);
            if (d == null) {
                return null; // not a clean positional notation
            }
            v[i] = d;
        }
        return switch (parts.length) {
            // 2-slot shorthand: morning + night by convention, but inherently ambiguous.
            case 2 -> FrequencyResult.ambiguous(new Slots(v[0], 0, v[1], 0), Pattern.DAILY);
            case 3 -> FrequencyResult.of(new Slots(v[0], v[1], v[2], 0), Pattern.DAILY);
            case 4 -> FrequencyResult.of(new Slots(v[0], v[1], v[2], v[3]), Pattern.DAILY);
            default -> null; // 1 or 5+ positions is not a schedule we recognise
        };
    }

    private static Double fraction(String token) {
        return switch (token) {
            case HALF -> 0.5;
            case QUARTER -> 0.25;
            case THREE_QUARTER -> 0.75;
            default -> {
                try {
                    yield Double.valueOf(token);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
        };
    }

    private static FrequencyResult daily(double m, double noon, double night, double bed) {
        return FrequencyResult.of(new Slots(m, noon, night, bed), Pattern.DAILY);
    }

    private static FrequencyResult pattern(Pattern p) {
        return FrequencyResult.of(Slots.NONE, p);
    }
}
