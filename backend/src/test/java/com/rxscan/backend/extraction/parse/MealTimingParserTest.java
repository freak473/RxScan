package com.rxscan.backend.extraction.parse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Meal timing is populated only from what the vision model read — never inferred from dose
 * notation (parser spec §Components 3, CLAUDE.md "No inferred fields"). Unrecognized or absent
 * input yields {@code null}.
 */
class MealTimingParserTest {

    @ParameterizedTest
    @CsvSource({
            "'AC',            BEFORE",
            "'before food',   BEFORE",
            "'empty stomach', BEFORE",
            "'PC',            AFTER",
            "'after food',    AFTER",
            "'with food',     WITH",
            "'Ac',            BEFORE",   // case-insensitive
            "'AFTER FOOD',    AFTER",
    })
    void recognized_meal_timings_map_to_enum(String input, Meal expected) {
        assertThat(MealTimingParser.parse(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gibberish", "1-0-1", "sometime", "food"})
    void unrecognized_input_is_null_never_inferred(String input) {
        assertThat(MealTimingParser.parse(input)).isNull();
    }

    @Test
    void null_and_blank_are_null() {
        assertThat(MealTimingParser.parse(null)).isNull();
        assertThat(MealTimingParser.parse("")).isNull();
        assertThat(MealTimingParser.parse("   ")).isNull();
    }
}
