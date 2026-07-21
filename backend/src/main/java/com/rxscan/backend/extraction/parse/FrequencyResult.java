package com.rxscan.backend.extraction.parse;

/**
 * Outcome of parsing a dose notation. Deliberately carries no confidence value — combining the
 * grammar's certainty with the vision model's per-field confidence is the orchestrator's job, so
 * the grammar stays a pure, model-agnostic function.
 *
 * @param recognized false when the notation matched no known form (orchestrator raises a flag)
 * @param ambiguous  true for shorthand that is inherently under-specified (e.g. 2-slot "1-1"),
 *                   so the orchestrator caps confidence even though a best-effort schedule exists
 */
public record FrequencyResult(Slots slots, Pattern pattern, boolean recognized, boolean ambiguous) {

    static FrequencyResult unrecognized() {
        return new FrequencyResult(Slots.NONE, Pattern.DAILY, false, false);
    }

    static FrequencyResult of(Slots slots, Pattern pattern) {
        return new FrequencyResult(slots, pattern, true, false);
    }

    static FrequencyResult ambiguous(Slots slots, Pattern pattern) {
        return new FrequencyResult(slots, pattern, true, true);
    }
}
