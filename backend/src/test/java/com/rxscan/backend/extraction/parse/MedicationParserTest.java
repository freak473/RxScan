package com.rxscan.backend.extraction.parse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end orchestration over the design's demo meds, asserting the exact flag set per case and
 * the CDSCO invariant that a flag NEVER carries a value.
 *
 * <p>Formulary matching is disabled (the {@code formulary_sku} catalog was dropped with the engine
 * plane — users-only v1, platformisation deferred; see CLAUDE.md), so this parser is built with
 * {@link FormularyMatcher#disabled()} — a plain unit test, no database, no Testcontainers. Every
 * resolved {@code drug().formularyId()} is therefore always {@code null}; the flag/frequency/
 * duration/meal assertions below are otherwise unchanged.
 */
class MedicationParserTest {

    private MedicationParser parser;

    @BeforeEach
    void setUp() {
        parser = new MedicationParser(FormularyMatcher.disabled());
    }

    private static FieldConfidence high() {
        return new FieldConfidence(0.95, 0.95, 0.95, 0.95, 0.95);
    }

    private static List<FlagReason> reasons(MedParseResult r) {
        return r.flags().stream().map(Flag::reason).toList();
    }

    @Test
    void clean_medicine_raises_no_flags_and_leaves_formulary_unresolved() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Augmentin 625 Duo", "625mg", "1-0-1", "5 days", "after food", high()));

        assertThat(r.drug().value()).isEqualTo("Augmentin 625 Duo"); // never rewritten
        assertThat(r.drug().formularyId()).isNull(); // formulary matching disabled
        assertThat(r.frequency().pattern()).isEqualTo(Pattern.DAILY);
        assertThat(r.frequency().slots()).isEqualTo(new Slots(1, 0, 1, 0));
        assertThat(r.mealTiming().value()).isEqualTo(Meal.AFTER);
        assertThat(r.duration().type()).isEqualTo(DurationType.DAYS);
        assertThat(r.duration().days()).isEqualTo(5);
        assertThat(r.flags()).isEmpty();
    }

    @Test
    void unreadable_strength_flags_strength_unreadable() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Augmentin 625 Duo", null, "1-0-1", "5 days", "after food",
                new FieldConfidence(0.95, 0.30, 0.95, 0.95, 0.95)));
        assertThat(reasons(r)).contains(FlagReason.STRENGTH_UNREADABLE);
    }

    @Test
    void unclear_duration_flags_duration_unclear() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Dolo 650", null, "1-0-1", "a while", null, high()));
        assertThat(r.duration().type()).isEqualTo(DurationType.UNSPECIFIED);
        assertThat(reasons(r)).contains(FlagReason.DURATION_UNCLEAR);
    }

    @Test
    void weekly_frequency_forces_a_confirm() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Dolo 650", null, "weekly", "4 weeks", null, high()));
        assertThat(r.frequency().pattern()).isEqualTo(Pattern.WEEKLY);
        assertThat(reasons(r)).contains(FlagReason.FREQ_NON_DAILY);
    }

    @Test
    void prn_is_not_treated_as_non_daily() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Dolo 650", null, "SOS", null, null, high()));
        assertThat(r.frequency().pattern()).isEqualTo(Pattern.PRN);
        assertThat(reasons(r)).doesNotContain(FlagReason.FREQ_NON_DAILY);
    }

    @Test
    void unrecognized_frequency_is_flagged() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Dolo 650", null, "scribble", "5 days", null, high()));
        assertThat(reasons(r)).contains(FlagReason.FREQ_UNRECOGNIZED);
    }

    @Test
    void unknown_low_confidence_name_flags_name_low_confidence() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Xyzzy Zztop", null, "1-0-1", "5 days", null,
                new FieldConfidence(0.40, 0.95, 0.95, 0.95, 0.95)));
        assertThat(r.drug().formularyId()).isNull();
        assertThat(reasons(r)).contains(FlagReason.NAME_LOW_CONFIDENCE);
    }

    @Test
    void absent_meal_timing_uses_slots_only_and_raises_no_meal_flag() {
        // No before/after-food note → meal is null (never inferred) and the schedule stands on the
        // morning/noon/night slots alone. A missing meal note is normal, not an anomaly.
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Augmentin 625 Duo", "625mg", "1-0-1", "5 days", null, high()));
        assertThat(r.mealTiming().value()).isNull();
        assertThat(r.frequency().slots()).isEqualTo(new Slots(1, 0, 1, 0));
        assertThat(r.flags().stream().map(Flag::field)).doesNotContain(FieldName.MEAL);
    }

    @Test
    void a_flag_structurally_cannot_carry_a_value() {
        // CDSCO "flag, don't correct": Flag has exactly {field, reason} — no value component.
        var components = Flag.class.getRecordComponents();
        assertThat(components).hasSize(2);
        assertThat(java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getName))
                .containsExactlyInAnyOrder("field", "reason");
    }
}
