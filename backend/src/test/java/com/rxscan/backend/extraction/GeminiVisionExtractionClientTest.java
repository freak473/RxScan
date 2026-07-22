package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.FieldConfidence;
import com.rxscan.backend.extraction.parse.VisionMedRaw;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * Pure unit test over {@link GeminiVisionExtractionClient#parseResponse(String)} — no Spring
 * context, no live call. Feeds a realistic canned Gemini {@code generateContent} response body
 * (candidates → content → parts → text, where {@code text} is itself a JSON array string, exactly
 * as the API returns it when {@code response_mime_type=application/json}).
 */
class GeminiVisionExtractionClientTest {

    // The model's inner JSON-array-as-string payload — verbatim reads, Indian Rx shorthand as-is.
    private static final String INNER_JSON = """
            [
              {
                "name": "Augmentin 625 Duo",
                "strength": "625mg",
                "doseNotation": "1-0-1",
                "duration": "5 days",
                "meal": "after food",
                "confidence": {"name": 0.95, "strength": 0.9, "doseNotation": 0.92, "duration": 0.88, "meal": 0.8}
              },
              {
                "name": "Dolo 650",
                "strength": null,
                "doseNotation": "weekly",
                "duration": "4 weeks",
                "meal": null,
                "confidence": {"name": 0.7, "strength": 0.0, "doseNotation": 0.6, "duration": 0.5, "meal": 0.0}
              }
            ]
            """;

    private static String canned(String innerJson) {
        // Real Gemini responses carry role/finishReason/safetyRatings/usageMetadata siblings too;
        // parseResponse must navigate to candidates[0].content.parts[0].text and ignore the rest.
        return """
                {
                  "candidates": [
                    {
                      "content": {
                        "parts": [
                          {"text": %s}
                        ]
                      },
                      "role": "model",
                      "finishReason": "STOP"
                    }
                  ],
                  "usageMetadata": {"promptTokenCount": 123, "candidatesTokenCount": 45}
                }
                """.formatted(jsonQuote(innerJson));
    }

    private static String jsonQuote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void parses_two_medicines_from_a_realistic_gemini_response() {
        List<VisionMedRaw> meds = GeminiVisionExtractionClient.parseResponse(canned(INNER_JSON));

        assertThat(meds).hasSize(2);

        VisionMedRaw augmentin = meds.get(0);
        assertThat(augmentin.name()).isEqualTo("Augmentin 625 Duo");
        assertThat(augmentin.strength()).isEqualTo("625mg");
        assertThat(augmentin.doseNotation()).isEqualTo("1-0-1");
        assertThat(augmentin.duration()).isEqualTo("5 days");
        assertThat(augmentin.meal()).isEqualTo("after food");
        assertThat(augmentin.confidence()).isEqualTo(new FieldConfidence(0.95, 0.9, 0.92, 0.88, 0.8));

        VisionMedRaw dolo = meds.get(1);
        assertThat(dolo.name()).isEqualTo("Dolo 650");
        assertThat(dolo.strength()).isNull();
        assertThat(dolo.doseNotation()).isEqualTo("weekly");
        assertThat(dolo.duration()).isEqualTo("4 weeks");
        assertThat(dolo.meal()).isNull();
        assertThat(dolo.confidence()).isEqualTo(new FieldConfidence(0.7, 0.0, 0.6, 0.5, 0.0));
    }

    @Test
    void missing_candidates_yields_an_empty_list() {
        List<VisionMedRaw> meds = GeminiVisionExtractionClient.parseResponse("{}");
        assertThat(meds).isEmpty();
    }

    @Test
    void empty_candidates_array_yields_an_empty_list() {
        List<VisionMedRaw> meds = GeminiVisionExtractionClient.parseResponse("{\"candidates\": []}");
        assertThat(meds).isEmpty();
    }

    private static GeminiVisionExtractionClient clientBoundTo(MockServerHolder holder) {
        RestClient.Builder builder = RestClient.builder();
        holder.server = MockRestServiceServer.bindTo(builder).build();
        return new GeminiVisionExtractionClient("k", "gemini-2.0-flash", "https://example/v1beta", builder);
    }

    /** Tiny mutable holder so the test can grab the bound server after the client is built. */
    private static final class MockServerHolder {
        MockRestServiceServer server;
    }

    @Test
    void upstream_429_maps_to_rate_limited_exception() {
        MockServerHolder h = new MockServerHolder();
        GeminiVisionExtractionClient client = clientBoundTo(h);
        h.server.expect(requestTo(Matchers.containsString(":generateContent")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.extract(new byte[]{1, 2, 3}, "image/png"))
                .isInstanceOf(VisionRateLimitedException.class);
    }

    @Test
    void upstream_server_error_maps_to_upstream_exception() {
        MockServerHolder h = new MockServerHolder();
        GeminiVisionExtractionClient client = clientBoundTo(h);
        h.server.expect(requestTo(Matchers.containsString(":generateContent")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));

        assertThatThrownBy(() -> client.extract(new byte[]{1, 2, 3}, "image/png"))
                .isInstanceOf(VisionUpstreamException.class);
    }
}
