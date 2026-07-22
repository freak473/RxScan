package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.FieldConfidence;
import com.rxscan.backend.extraction.parse.VisionMedRaw;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared, provider-agnostic pieces of vision-model response handling: the verbatim-only Indian-Rx
 * prompt every {@link VisionExtractionClient} sends, and a tolerant parser that turns whatever
 * medicines payload a model's text output contains into {@link VisionMedRaw}s.
 *
 * <p>Package-private and static-only: this is glue shared between {@link
 * GeminiVisionExtractionClient} and {@link OpenAiCompatibleVisionExtractionClient}, not part of the
 * public extraction API.
 */
final class VisionResponseSupport {

    // --- prompt: verbatim reads only; Indian Rx shorthand is expected as-is, never expanded ---
    static final String PROMPT = """
            You are reading a photo of a handwritten or printed Indian medical prescription.

            For each medicine on the prescription, extract exactly these fields, VERBATIM as
            written on the paper:
              - name: the medicine/brand name as written
              - strength: the dose strength as written (e.g. "625mg", "40mg"), or null if absent
              - doseNotation: the frequency exactly as written (e.g. "1-0-1", "BD", "TDS", "OD",
                "HS", "SOS", "1-1-1-1"). Indian shorthand (BD/TDS/OD/HS/SOS, AC/PC, weekly,
                alternate day) is expected — copy it exactly. Do NOT expand "1-0-1" into words,
                do NOT normalize abbreviations, do NOT guess a schedule that isn't written.
              - duration: the course length as written (e.g. "5 days", "x5d", "4 weeks"), or null
                if absent. Do NOT normalize or convert units.
              - meal: the meal relation as written (e.g. "before food", "after food", "AC", "PC"),
                or null if absent. Do NOT infer a meal relation that isn't written.

            Also include a "confidence" object per medicine with a 0.0-1.0 score for EACH of the
            five fields above (name, strength, doseNotation, duration, meal), reflecting how
            legible/certain that specific field was.

            Respond with ONLY a JSON array, one object per medicine, shaped exactly like:
            [{"name": "...", "strength": "...", "doseNotation": "...", "duration": "...",
              "meal": "...", "confidence": {"name": 0.0, "strength": 0.0, "doseNotation": 0.0,
              "duration": 0.0, "meal": 0.0}}]

            No prose, no markdown fences, no explanation — JSON only.
            """;

    // --- medicines payload shape ---
    private static final String RES_NAME = "name";
    private static final String RES_STRENGTH = "strength";
    private static final String RES_DOSE_NOTATION = "doseNotation";
    private static final String RES_DURATION = "duration";
    private static final String RES_MEAL = "meal";
    private static final String RES_CONFIDENCE = "confidence";
    private static final String RES_MEDICINES = "medicines";

    // --- tolerant code-fence stripping (some models wrap JSON in ```json ... ``` anyway) ---
    private static final String FENCE_JSON_PREFIX = "```json";
    private static final String FENCE_PREFIX = "```";
    private static final String FENCE_SUFFIX = "```";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private VisionResponseSupport() {
    }

    /**
     * Tolerantly parses a model's raw text output into the medicines it read.
     *
     * <p>Handles three shapes: a bare JSON array, a {@code {"medicines": [...]}} object, and either
     * wrapped in a ```json ... ``` (or bare ``` ... ```) code fence. Anything else — including
     * unparsable text — yields an empty list rather than throwing, the safer default for a
     * non-advisory scribe.
     */
    static List<VisionMedRaw> parseMedicines(String modelText) {
        if (modelText == null) {
            return List.of();
        }

        String stripped = stripCodeFence(modelText.trim());
        if (stripped.isBlank()) {
            return List.of();
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(stripped);
        } catch (RuntimeException e) {
            return List.of();
        }

        JsonNode meds;
        if (root.isArray()) {
            meds = root;
        } else if (root.isObject() && root.path(RES_MEDICINES).isArray()) {
            meds = root.path(RES_MEDICINES);
        } else {
            return List.of();
        }

        List<VisionMedRaw> result = new ArrayList<>();
        for (JsonNode med : meds) {
            result.add(toVisionMedRaw(med));
        }
        return result;
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith(FENCE_PREFIX) || !text.endsWith(FENCE_SUFFIX)) {
            return text;
        }
        String withoutPrefix = text.startsWith(FENCE_JSON_PREFIX)
                ? text.substring(FENCE_JSON_PREFIX.length())
                : text.substring(FENCE_PREFIX.length());
        String withoutSuffix = withoutPrefix.substring(0, withoutPrefix.length() - FENCE_SUFFIX.length());
        return withoutSuffix.trim();
    }

    private static VisionMedRaw toVisionMedRaw(JsonNode med) {
        JsonNode conf = med.path(RES_CONFIDENCE);
        FieldConfidence confidence = new FieldConfidence(
                conf.path(RES_NAME).asDouble(0.0),
                conf.path(RES_STRENGTH).asDouble(0.0),
                conf.path(RES_DOSE_NOTATION).asDouble(0.0),
                conf.path(RES_DURATION).asDouble(0.0),
                conf.path(RES_MEAL).asDouble(0.0));

        return new VisionMedRaw(
                textOrNull(med, RES_NAME),
                textOrNull(med, RES_STRENGTH),
                textOrNull(med, RES_DOSE_NOTATION),
                textOrNull(med, RES_DURATION),
                textOrNull(med, RES_MEAL),
                confidence);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return (value.isMissingNode() || value.isNull()) ? null : value.asString();
    }
}
