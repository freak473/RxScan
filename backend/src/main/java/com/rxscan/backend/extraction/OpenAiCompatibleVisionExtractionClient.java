package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.VisionMedRaw;
import org.springframework.http.HttpHeaders;
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
 * {@link VisionExtractionClient} for any vision model that speaks the OpenAI {@code
 * chat/completions} request/response shape — this serves both xAI Grok and Moonshot Kimi, which
 * are OpenAI-API-compatible. Sends the image as a base64 {@code data:} URL inline in the message
 * content alongside the same verbatim-reads prompt Gemini uses ({@link VisionResponseSupport}).
 *
 * <p>Response parsing is split into the package-private static {@link #parseResponse(String)} so
 * it is unit-testable without a live API call or a Spring context.
 */
public final class OpenAiCompatibleVisionExtractionClient implements VisionExtractionClient {

    // --- request shape (OpenAI chat/completions) ---
    private static final String PATH_CHAT_COMPLETIONS = "/chat/completions";
    private static final String REQ_MODEL = "model";
    private static final String REQ_TEMPERATURE = "temperature";
    private static final String REQ_MESSAGES = "messages";
    private static final String REQ_ROLE = "role";
    private static final String REQ_ROLE_USER = "user";
    private static final String REQ_CONTENT = "content";
    private static final String REQ_TYPE = "type";
    private static final String REQ_TYPE_TEXT = "text";
    private static final String REQ_TYPE_IMAGE_URL = "image_url";
    private static final String REQ_TEXT = "text";
    private static final String REQ_IMAGE_URL = "image_url";
    private static final String REQ_URL = "url";
    private static final String DATA_URL_TEMPLATE = "data:%s;base64,%s";

    // --- auth ---
    private static final String BEARER_PREFIX = "Bearer ";

    // --- response shape (choices[0].message.content is a string, itself the medicines payload) ---
    private static final String RES_CHOICES = "choices";
    private static final String RES_MESSAGE = "message";
    private static final String RES_CONTENT = "content";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static final String ERR_RATE_LIMITED = "Vision model rate-limited (HTTP 429)";
    private static final String ERR_UPSTREAM = "Vision model call failed";

    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public OpenAiCompatibleVisionExtractionClient(String apiKey, String model, String baseUrl,
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
                    .uri(PATH_CHAT_COMPLETIONS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + apiKey)
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
        String dataUrl = DATA_URL_TEMPLATE.formatted(mediaType, Base64.getEncoder().encodeToString(image));

        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put(REQ_TYPE, REQ_TYPE_TEXT);
        textPart.put(REQ_TEXT, VisionResponseSupport.PROMPT);

        Map<String, Object> imagePart = new LinkedHashMap<>();
        imagePart.put(REQ_TYPE, REQ_TYPE_IMAGE_URL);
        imagePart.put(REQ_IMAGE_URL, Map.of(REQ_URL, dataUrl));

        Map<String, Object> message = new LinkedHashMap<>();
        message.put(REQ_ROLE, REQ_ROLE_USER);
        message.put(REQ_CONTENT, List.of(textPart, imagePart));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put(REQ_MODEL, model);
        body.put(REQ_TEMPERATURE, 0);
        body.put(REQ_MESSAGES, List.of(message));
        return body;
    }

    /**
     * Parses a raw OpenAI-compatible {@code chat/completions} response body into the medicines it
     * read. Package-private and static so this can be unit-tested without a live call.
     *
     * <p>Design choice: missing {@code choices}/{@code message}/{@code content} yields an empty
     * list rather than throwing — the caller sees "no medicines found" rather than a hard failure,
     * which is the safer default for a non-advisory scribe.
     */
    static List<VisionMedRaw> parseResponse(String openAiJsonBody) {
        JsonNode root = MAPPER.readTree(openAiJsonBody);
        JsonNode choices = root.path(RES_CHOICES);
        if (!choices.isArray() || choices.isEmpty()) {
            return List.of();
        }

        String content = choices.get(0).path(RES_MESSAGE).path(RES_CONTENT).asString(null);
        if (content == null || content.isBlank()) {
            return List.of();
        }

        return VisionResponseSupport.parseMedicines(content);
    }
}
