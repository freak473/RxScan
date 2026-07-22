package com.rxscan.backend.extraction.parse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Duration is populated only from what the model read; absent or unparseable → UNSPECIFIED
 * (parser spec §Components 4). Days are bounds-checked 1..60 (matches the Android numeric flag
 * validator); out-of-range collapses to UNSPECIFIED.
 */
class DurationParserTest {

    @ParameterizedTest
    @CsvSource({
            "'5 days',  5",
            "'x5d',     5",
            "'5/7',     5",
            "'1 week',  7",
            "'x1w',     7",
            "'2 weeks', 14",
            "'X5D',     5",   // case-insensitive
            "'60 days', 60",  // upper bound inclusive
    })
    void days_and_weeks_parse_to_day_count(String input, int days) {
        DurationResult r = DurationParser.parse(input);
        assertThat(r.type()).isEqualTo(DurationType.DAYS);
        assertThat(r.days()).isEqualTo(days);
    }

    @ParameterizedTest
    @ValueSource(strings = {"continue", "ongoing", "regular", "CONTINUE"})
    void ongoing_words_map_to_ongoing_with_no_days(String input) {
        DurationResult r = DurationParser.parse(input);
        assertThat(r.type()).isEqualTo(DurationType.ONGOING);
        assertThat(r.days()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a while", "??", "sometime", "0 days", "61 days", "100 days"})
    void junk_and_out_of_range_are_unspecified(String input) {
        DurationResult r = DurationParser.parse(input);
        assertThat(r.type()).isEqualTo(DurationType.UNSPECIFIED);
        assertThat(r.days()).isNull();
    }

    @Test
    void null_and_blank_are_unspecified() {
        assertThat(DurationParser.parse(null).type()).isEqualTo(DurationType.UNSPECIFIED);
        assertThat(DurationParser.parse("").type()).isEqualTo(DurationType.UNSPECIFIED);
        assertThat(DurationParser.parse("   ").type()).isEqualTo(DurationType.UNSPECIFIED);
    }
}
