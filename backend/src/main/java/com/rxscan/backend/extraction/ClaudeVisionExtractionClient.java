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
 * {@link VisionExtractionClient} for Anthropic's Claude models via the Messages API
 * ({@code POST /v1/messages}). Sends the image as a base64-encoded {@code image} content block
 * alongside the same verbatim-reads prompt the other providers use ({@link
 * VisionResponseSupport}).
 *
 * <p>Response parsing is split into the package-private static {@link #parseResponse(String)} so
 * it is unit-testable without a live API call or a Spring context, mirroring {@link
 * OpenAiCompatibleVisionExtractionClient}.
 */
public final class ClaudeVisionExtractionClient implements VisionExtractionClient {

    // --- request shape (Anthropic Messages API) ---
    private static final String PATH_MESSAGES = "/v1/messages";
    private static final int MAX_TOKENS = 2048;
    private static final String REQ_MODEL = "model";
    private static final String REQ_MAX_TOKENS = "max_tokens";
    private static final String REQ_MESSAGES = "messages";
    private static final String REQ_ROLE = "role";
    private static final String REQ_ROLE_USER = "user";
    private static final String REQ_CONTENT = "content";
    private static final String REQ_TYPE = "type";
    private static final String REQ_TYPE_IMAGE = "image";
    private static final String REQ_TYPE_TEXT = "text";
    private static final String REQ_TEXT = "text";
    private static final String REQ_SOURCE = "source";
    private static final String REQ_SOURCE_TYPE = "type";
    private static final String REQ_SOURCE_TYPE_BASE64 = "base64";
    private static final String REQ_MEDIA_TYPE = "media_type";
    private static final String REQ_DATA = "data";

    // --- auth / versioning headers ---
    private static final String HEADER_API_KEY = "x-api-key";
    private static final String HEADER_ANTHROPIC_VERSION = "anthropic-version";
    private static final String ANTHROPIC_VERSION_VALUE = "2023-06-01";

    // --- response shape (content[] holds text/tool_use/etc. blocks; find the first "text" one) ---
    private static final String RES_CONTENT = "content";
    private static final String RES_TYPE = "type";
    private static final String RES_TYPE_TEXT = "text";
    private static final String RES_TEXT = "text";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String ERR_RATE_LIMITED = "Vision model rate-limited (HTTP 429)";
    private static final String ERR_UPSTREAM = "Vision model call failed";

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public ClaudeVisionExtractionClient(String apiKey, String model, String baseUrl,
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
                    .uri(PATH_MESSAGES)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HEADER_API_KEY, apiKey)
                    .header(HEADER_ANTHROPIC_VERSION, ANTHROPIC_VERSION_VALUE)
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
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(REQ_SOURCE_TYPE, REQ_SOURCE_TYPE_BASE64);
        source.put(REQ_MEDIA_TYPE, mediaType);
        source.put(REQ_DATA, Base64.getEncoder().encodeToString(image));

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put(REQ_TYPE, REQ_TYPE_IMAGE);
        imagePart.put(REQ_SOURCE, source);

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put(REQ_TYPE, REQ_TYPE_TEXT);
        textPart.put(REQ_TEXT, VisionResponseSupport.PROMPT);

        Map<String, Object> message = new LinkedHashMap<>();
        message.put(REQ_ROLE, REQ_ROLE_USER);
        message.put(REQ_CONTENT, List.of(imagePart, textPart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(REQ_MODEL, model);
        body.put(REQ_MAX_TOKENS, MAX_TOKENS);
        body.put(REQ_MESSAGES, List.of(message));
        return body;
    }

    /**
     * Parses a raw Anthropic Messages API response body into the medicines it read. Package-private
     * and static so this can be unit-tested without a live call.
     *
     * <p>Design choice: missing {@code content}, an empty {@code content} array, or a {@code
     * content} array with no {@code "type": "text"} block all yield an empty list rather than
     * throwing — the caller sees "no medicines found" rather than a hard failure, which is the
     * safer default for a non-advisory scribe.
     */
    static List<VisionMedRaw> parseResponse(String anthropicJsonBody) {
        JsonNode root = MAPPER.readTree(anthropicJsonBody);
        JsonNode content = root.path(RES_CONTENT);
        if (!content.isArray() || content.isEmpty()) {
            return List.of();
        }

        String text = null;
        for (JsonNode block : content) {
            if (RES_TYPE_TEXT.equals(block.path(RES_TYPE).asString(null))) {
                text = block.path(RES_TEXT).asString(null);
                break;
            }
        }
        if (text == null || text.isBlank()) {
            return List.of();
        }

        return VisionResponseSupport.parseMedicines(text);
    }
}
