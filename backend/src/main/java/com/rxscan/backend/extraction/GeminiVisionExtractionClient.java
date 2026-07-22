package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.VisionMedRaw;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

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

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    private static final String ERR_RATE_LIMITED = "Vision model rate-limited (HTTP 429)";
    private static final String ERR_UPSTREAM = "Vision model call failed";

    public GeminiVisionExtractionClient(String apiKey, String model, String baseUrl,
                                        RestClient.Builder builder) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    @Override
    public List<VisionMedRaw> extract(byte[] image, String mediaType) {
        String requestBody = MAPPER.writeValueAsString(buildRequestBody(image, mediaType));

        String responseBody;
        try {
            responseBody = restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(PATH_TEMPLATE)
                            .queryParam(QUERY_PARAM_API_KEY, apiKey)
                            .build(model))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.TooManyRequests e) {
            // Quota / rate limit — transient. Surface as 503 (retry) not an opaque 500.
            throw new VisionRateLimitedException(ERR_RATE_LIMITED, e);
        } catch (RestClientException e) {
            // Any other upstream 4xx/5xx or transport failure → 502 (bad gateway).
            throw new VisionUpstreamException(ERR_UPSTREAM, e);
        }

        return parseResponse(responseBody);
    }

    private Map<String, Object> buildRequestBody(byte[] image, String mediaType) {
        Map<String, Object> inlineData = new LinkedHashMap<>();
        inlineData.put(REQ_MIME_TYPE, mediaType);
        inlineData.put(REQ_DATA, Base64.getEncoder().encodeToString(image));

        Map<String, Object> imagePart = Map.of(REQ_INLINE_DATA, inlineData);
        Map<String, Object> textPart = Map.of(REQ_TEXT, VisionResponseSupport.PROMPT);
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

        return VisionResponseSupport.parseMedicines(text);
    }
}
