package com.rxscan.backend.extraction.parse;

import java.util.ArrayList;
import java.util.List;

import static com.rxscan.backend.extraction.parse.ParserThresholds.AMBIGUOUS_FREQ_CAP;
import static com.rxscan.backend.extraction.parse.ParserThresholds.CONF_MIN;

/**
 * Orchestrates the deterministic layer: takes one medicine as the vision model read it and produces
 * a structured, confidence-scored, flag-annotated {@link MedParseResult}. It calls the four units
 * ({@link FrequencyGrammar}, {@link FormularyMatcher}, {@link MealTimingParser},
 * {@link DurationParser}) and applies the flag policy from the parser spec §Components 5.
 *
 * <p>Two invariants live here and are load-bearing:
 * <ul>
 *   <li><b>Flag, don't correct.</b> Every displayed {@code value} is exactly what was read; a
 *       problem raises a {@link Flag} (field + reason, no value), never a substitution.
 *   <li><b>Non-daily forces a confirm.</b> A weekly/alternate-day pattern always flags — the
 *       highest-harm misread.
 * </ul>
 */
public final class MedicationParser {

    private static final String EMPTY = "";
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    private final FormularyMatcher matcher;

    public MedicationParser(FormularyMatcher matcher) {
        this.matcher = matcher;
    }

    public MedParseResult parse(VisionMedRaw raw) {
        FieldConfidence conf = raw.confidence();
        List<Flag> flags = new ArrayList<>();

        // --- drug: resolve against the formulary, raise confidence, never rewrite the name ---
        FormularyMatch match = matcher.match(raw.name(), raw.strength());
        double drugConf = match.matched() ? Math.max(conf.name(), match.score()) : conf.name();
        if (!match.matched() && conf.name() < CONF_MIN) {
            flags.add(new Flag(FieldName.DRUG, FlagReason.NAME_LOW_CONFIDENCE));
        }
        var drug = new MedParseResult.Drug(raw.name(), match.formularyId(), drugConf);

        // --- strength: unreadable, or conflicts with the resolved SKU (anomaly) ---
        if (isBlank(raw.strength()) || conf.strength() < CONF_MIN) {
            flags.add(new Flag(FieldName.STRENGTH, FlagReason.STRENGTH_UNREADABLE));
        } else if (match.matched() && !isBlank(match.skuStrength())
                && !normStrength(raw.strength()).equals(normStrength(match.skuStrength()))) {
            flags.add(new Flag(FieldName.STRENGTH, FlagReason.STRENGTH_ANOMALY));
        }
        var strength = new MedParseResult.Strength(raw.strength(), conf.strength());

        // --- frequency: the highest-harm surface (never guess; non-daily forces confirm) ---
        FrequencyResult fr = FrequencyGrammar.parse(raw.doseNotation());
        double freqConf;
        if (!fr.recognized()) {
            freqConf = 0.0;
            flags.add(new Flag(FieldName.FREQUENCY, FlagReason.FREQ_UNRECOGNIZED));
        } else {
            freqConf = fr.ambiguous() ? Math.min(conf.doseNotation(), AMBIGUOUS_FREQ_CAP)
                    : conf.doseNotation();
            if (fr.pattern() == Pattern.WEEKLY || fr.pattern() == Pattern.ALTERNATE_DAY) {
                flags.add(new Flag(FieldName.FREQUENCY, FlagReason.FREQ_NON_DAILY));
            }
        }
        var frequency = new MedParseResult.Frequency(raw.doseNotation(), fr.slots(), fr.pattern(), freqConf);

        // --- meal: never inferred; low confidence on a read value flags for re-check ---
        Meal mealValue = MealTimingParser.parse(raw.meal());
        if (!isBlank(raw.meal()) && conf.meal() < CONF_MIN) {
            flags.add(new Flag(FieldName.MEAL, FlagReason.FIELD_LOW_CONFIDENCE));
        }
        var mealTiming = new MedParseResult.MealTiming(mealValue, conf.meal());

        // --- duration: present but unparseable → unclear ---
        DurationResult dr = DurationParser.parse(raw.duration());
        if (!isBlank(raw.duration()) && dr.type() == DurationType.UNSPECIFIED) {
            flags.add(new Flag(FieldName.DURATION, FlagReason.DURATION_UNCLEAR));
        }
        var duration = new MedParseResult.Duration(dr.type(), dr.days(), conf.duration());

        return new MedParseResult(drug, strength, frequency, mealTiming, duration, List.copyOf(flags));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Strength equality for the cross-check: case- and space-insensitive ("625 mg" ≡ "625mg"). */
    private static String normStrength(String s) {
        return WHITESPACE.matcher(s.toLowerCase()).replaceAll(EMPTY);
    }
}
