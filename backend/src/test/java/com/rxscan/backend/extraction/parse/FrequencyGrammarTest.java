package com.rxscan.backend.extraction.parse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frequency grammar is the highest-harm surface (weekly-misread-as-daily), so it is
 * exhaustively table-tested. Notations are drawn from tech-design §2.3 and the parser spec
 * (docs/superpowers/specs/2026-07-20-rx-deterministic-parser-design.md).
 */
class FrequencyGrammarTest {

    // ---- positional: morning-noon-night(-bedtime), dose count per slot preserved ----
    @ParameterizedTest
    @CsvSource({
            "'1-0-1', 1, 0, 1, 0",
            "'1-1-1', 1, 1, 1, 0",
            "'0-0-1', 0, 0, 1, 0",
            "'2-0-2', 2, 0, 2, 0",
            "'0.5-0-0.5', 0.5, 0, 0.5, 0",
            "'½-0-½', 0.5, 0, 0.5, 0",
            "'1-0-0-1', 1, 0, 0, 1",   // 4th position = bedtime
    })
    void positional_unambiguous_maps_to_daily_slots(
            String input, double m, double noon, double night, double bed) {
        FrequencyResult r = FrequencyGrammar.parse(input);
        assertThat(r.recognized()).isTrue();
        assertThat(r.ambiguous()).isFalse();
        assertThat(r.pattern()).isEqualTo(Pattern.DAILY);
        assertThat(r.slots()).isEqualTo(new Slots(m, noon, night, bed));
    }

    @Test
    void two_slot_shorthand_is_morning_night_by_convention_but_ambiguous() {
        FrequencyResult r = FrequencyGrammar.parse("1-1");
        assertThat(r.recognized()).isTrue();
        assertThat(r.pattern()).isEqualTo(Pattern.DAILY);
        assertThat(r.slots()).isEqualTo(new Slots(1, 0, 1, 0));
        assertThat(r.ambiguous()).as("2-slot shorthand is inherently ambiguous").isTrue();
    }

    // ---- Latin / abbreviations ----
    @ParameterizedTest
    @CsvSource({
            "'OD',            1, 0, 0, 0",
            "'once daily',    1, 0, 0, 0",
            "'BD',            1, 0, 1, 0",
            "'BID',           1, 0, 1, 0",
            "'twice daily',   1, 0, 1, 0",
            "'TDS',           1, 1, 1, 0",
            "'TID',           1, 1, 1, 0",
            "'thrice daily',  1, 1, 1, 0",
            "'QID',           1, 1, 1, 1",
            "'QDS',           1, 1, 1, 1",
            "'HS',            0, 0, 0, 1",
            "'at bedtime',    0, 0, 0, 1",
    })
    void latin_abbreviations_expand_to_daily_slots(
            String input, double m, double noon, double night, double bed) {
        FrequencyResult r = FrequencyGrammar.parse(input);
        assertThat(r.recognized()).isTrue();
        assertThat(r.pattern()).isEqualTo(Pattern.DAILY);
        assertThat(r.slots()).isEqualTo(new Slots(m, noon, night, bed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOS", "PRN", "as needed"})
    void prn_has_no_schedule(String input) {
        FrequencyResult r = FrequencyGrammar.parse(input);
        assertThat(r.recognized()).isTrue();
        assertThat(r.pattern()).isEqualTo(Pattern.PRN);
        assertThat(r.slots()).isEqualTo(new Slots(0, 0, 0, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"STAT", "immediately"})
    void stat_is_one_off(String input) {
        FrequencyResult r = FrequencyGrammar.parse(input);
        assertThat(r.recognized()).isTrue();
        assertThat(r.pattern()).isEqualTo(Pattern.STAT);
    }

    // ---- non-daily: recognized, but the orchestrator will force a confirm ----
    @ParameterizedTest
    @ValueSource(strings = {"weekly", "once a week", "OW"})
    void weekly_is_recognized_as_non_daily(String input) {
        FrequencyResult r = FrequencyGrammar.parse(input);
        assertThat(r.recognized()).isTrue();
        assertThat(r.pattern()).isEqualTo(Pattern.WEEKLY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EOD", "alternate day", "alt day"})
    void alternate_day_is_recognized_as_non_daily(String input) {
        FrequencyResult r = FrequencyGrammar.parse(input);
        assertThat(r.recognized()).isTrue();
        assertThat(r.pattern()).isEqualTo(Pattern.ALTERNATE_DAY);
    }

    // ---- case / whitespace tolerance ----
    @Test
    void is_case_and_space_insensitive() {
        assertThat(FrequencyGrammar.parse(" 1 - 0 - 1 ").slots()).isEqualTo(new Slots(1, 0, 1, 0));
        assertThat(FrequencyGrammar.parse("bd").pattern()).isEqualTo(Pattern.DAILY);
        assertThat(FrequencyGrammar.parse("Tds").slots()).isEqualTo(new Slots(1, 1, 1, 0));
    }

    // ---- junk → not recognized (the orchestrator flags it) ----
    @ParameterizedTest
    @ValueSource(strings = {"??", "1-2-3-4-5", "gibberish", "-", "1--1"})
    void unrecognized_input_is_flagged(String input) {
        FrequencyResult r = FrequencyGrammar.parse(input);
        assertThat(r.recognized()).isFalse();
    }

    @Test
    void null_and_blank_are_not_recognized() {
        assertThat(FrequencyGrammar.parse(null).recognized()).isFalse();
        assertThat(FrequencyGrammar.parse("").recognized()).isFalse();
        assertThat(FrequencyGrammar.parse("   ").recognized()).isFalse();
    }
}
