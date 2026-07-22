package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.FieldConfidence;
import com.rxscan.backend.extraction.parse.VisionMedRaw;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link VisionExtractionClient} backed by a single Gemini {@code generateContent} call
 * (tech-design §2.3: one vision call per prescription). Sends the image inline as base64 alongside
 * a prompt that demands verbatim reads — no expansion, no normalization, no guessing — plus a
 * per-field confidence score, and asks for JSON-only output ({@code response_mime_type}).
 *
 * <p>Response parsing is split into the package-private static {@link #parseResponse(String)} so
 * it is unit-testable without a live API call or a Spring context.
 */
public final class GeminiVisionExtractionClient implements VisionExtractionClient {

    // --- prompt: verbatim reads only; Indian Rx shorthand is expected as-is, never expanded ---
    private static final String PROMPT = """
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

    // --- request shape (Gemini generateContent) ---
    private static final String PATH_TEMPLATE = "/models/{model}:generateContent";
    private static final String QUERY_PARAM_API_KEY = "key";
    private static final String REQ_CONTENTS = "contents";
    private static final String REQ_PARTS = "parts";
    private static final String REQ_TEXT = "text";
    private static final String REQ_INLINE_DATA = "inline_data";
    private static final String REQ_MIME_TYPE = "mime_type";
    private static final String REQ_DATA = "data";
    private static final String REQ_GENERATION_CONFIG = "generationConfig";
    private static final String REQ_TEMPERATURE = "temperature";
    private static final String REQ_RESPONSE_MIME_TYPE = "response_mime_type";
    private static final String RESPONSE_MIME_TYPE_JSON = "application/json";

    // --- response shape (candidates[0].content.parts[0].text is itself a JSON array string) ---
    private static final String RES_CANDIDATES = "candidates";
    private static final String RES_CONTENT = "content";
    private static final String RES_PARTS = "parts";
    private static final String RES_TEXT = "text";
    private static final String RES_NAME = "name";
    private static final String RES_STRENGTH = "strength";
    private static final String RES_DOSE_NOTATION = "doseNotation";
    private static final String RES_DURATION = "duration";
    private static final String RES_MEAL = "meal";
    private static final String RES_CONFIDENCE = "confidence";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public GeminiVisionExtractionClient(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    @Override
    public List<VisionMedRaw> extract(byte[] image, String mediaType) {
        String requestBody = MAPPER.writeValueAsString(buildRequestBody(image, mediaType));

        String responseBody = restClient.post()
                .uri(uriBuilder -> uriBuilder.path(PATH_TEMPLATE)
                        .queryParam(QUERY_PARAM_API_KEY, apiKey)
                        .build(model))
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

        return parseResponse(responseBody);
    }

    private Map<String, Object> buildRequestBody(byte[] image, String mediaType) {
        Map<String, Object> inlineData = new LinkedHashMap<>();
        inlineData.put(REQ_MIME_TYPE, mediaType);
        inlineData.put(REQ_DATA, Base64.getEncoder().encodeToString(image));

        Map<String, Object> imagePart = Map.of(REQ_INLINE_DATA, inlineData);
        Map<String, Object> textPart = Map.of(REQ_TEXT, PROMPT);
        Map<String, Object> content = Map.of(REQ_PARTS, List.of(textPart, imagePart));

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put(REQ_TEMPERATURE, 0);
        generationConfig.put(REQ_RESPONSE_MIME_TYPE, RESPONSE_MIME_TYPE_JSON);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(REQ_CONTENTS, List.of(content));
        body.put(REQ_GENERATION_CONFIG, generationConfig);
        return body;
    }

    /**
     * Parses a raw Gemini {@code generateContent} response body into the medicines it read.
     * Package-private and static so this can be unit-tested without a live call.
     *
     * <p>Design choice: a missing/empty {@code candidates} array (blocked prompt, safety filter,
     * malformed body) yields an empty list rather than throwing — the caller sees "no medicines
     * found" rather than a hard failure, which is the safer default for a non-advisory scribe.
     */
    static List<VisionMedRaw> parseResponse(String geminiJsonBody) {
        JsonNode root = MAPPER.readTree(geminiJsonBody);
        JsonNode candidates = root.path(RES_CANDIDATES);
        if (!candidates.isArray() || candidates.isEmpty()) {
            return List.of();
        }

        JsonNode parts = candidates.get(0).path(RES_CONTENT).path(RES_PARTS);
        if (!parts.isArray() || parts.isEmpty()) {
            return List.of();
        }

        String text = parts.get(0).path(RES_TEXT).asString(null);
        if (text == null || text.isBlank()) {
            return List.of();
        }

        JsonNode meds = MAPPER.readTree(text);
        if (!meds.isArray()) {
            return List.of();
        }

        List<VisionMedRaw> result = new ArrayList<>();
        for (JsonNode med : meds) {
            result.add(toVisionMedRaw(med));
        }
        return result;
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
