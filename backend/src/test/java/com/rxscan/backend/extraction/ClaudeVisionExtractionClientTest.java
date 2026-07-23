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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pure unit tests over {@link ClaudeVisionExtractionClient#parseResponse(String)} (no live call)
 * plus {@link MockRestServiceServer} tests for the request shape and error mapping, mirroring
 * {@link OpenAiCompatibleVisionExtractionClientTest}. Talks to Anthropic's Messages API
 * ({@code POST /v1/messages}).
 */
class ClaudeVisionExtractionClientTest {

    private static final String MEDICINES_JSON_ARRAY = """
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

    private static String canned(String content) {
        // Real Anthropic Messages API responses carry id/type/role/model/stop_reason/usage
        // siblings too; parseResponse must navigate to content[] and find the first "text" block.
        return """
                {
                  "id": "msg_abc123",
                  "type": "message",
                  "role": "assistant",
                  "model": "claude-sonnet-5",
                  "content": [
                    {"type": "text", "text": %s}
                  ],
                  "stop_reason": "end_turn",
                  "usage": {"input_tokens": 123, "output_tokens": 45}
                }
                """.formatted(jsonQuote(content));
    }

    private static String jsonQuote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void parses_two_medicines_from_a_realistic_anthropic_shaped_response() {
        List<VisionMedRaw> meds =
                ClaudeVisionExtractionClient.parseResponse(canned(MEDICINES_JSON_ARRAY));

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
    void missing_content_yields_an_empty_list() {
        List<VisionMedRaw> meds = ClaudeVisionExtractionClient.parseResponse("{}");
        assertThat(meds).isEmpty();
    }

    @Test
    void empty_content_array_yields_an_empty_list() {
        List<VisionMedRaw> meds =
                ClaudeVisionExtractionClient.parseResponse("{\"content\": []}");
        assertThat(meds).isEmpty();
    }

    @Test
    void content_with_no_text_block_yields_an_empty_list() {
        List<VisionMedRaw> meds = ClaudeVisionExtractionClient.parseResponse(
                "{\"content\": [{\"type\": \"tool_use\", \"id\": \"x\"}]}");
        assertThat(meds).isEmpty();
    }

    private static ClaudeVisionExtractionClient clientBoundTo(MockServerHolder holder) {
        RestClient.Builder builder = RestClient.builder();
        holder.server = MockRestServiceServer.bindTo(builder).build();
        return new ClaudeVisionExtractionClient("sk-ant-test-key", "claude-sonnet-5",
                "https://example", builder);
    }

    /** Tiny mutable holder so the test can grab the bound server after the client is built. */
    private static final class MockServerHolder {
        MockRestServiceServer server;
    }

    @Test
    void upstream_429_maps_to_rate_limited_exception() {
        MockServerHolder h = new MockServerHolder();
        ClaudeVisionExtractionClient client = clientBoundTo(h);
        h.server.expect(requestTo(Matchers.containsString("/v1/messages")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"error\":{\"message\":\"rate limited\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.extract(new byte[]{1, 2, 3}, "image/png"))
                .isInstanceOf(VisionRateLimitedException.class);
    }

    @Test
    void upstream_server_error_maps_to_upstream_exception() {
        MockServerHolder h = new MockServerHolder();
        ClaudeVisionExtractionClient client = clientBoundTo(h);
        h.server.expect(requestTo(Matchers.containsString("/v1/messages")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));

        assertThatThrownBy(() -> client.extract(new byte[]{1, 2, 3}, "image/png"))
                .isInstanceOf(VisionUpstreamException.class);
    }

    @Test
    void sends_api_key_and_version_headers_and_base64_image_and_parses_one_medicine() {
        MockServerHolder h = new MockServerHolder();
        ClaudeVisionExtractionClient client = clientBoundTo(h);

        String oneMed = """
                [
                  {
                    "name": "Pantocid",
                    "strength": "40mg",
                    "doseNotation": "OD",
                    "duration": "10 days",
                    "meal": "before food",
                    "confidence": {"name": 0.9, "strength": 0.85, "doseNotation": 0.8, "duration": 0.75, "meal": 0.7}
                  }
                ]
                """;

        h.server.expect(requestTo(Matchers.containsString("/v1/messages")))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("x-api-key", "sk-ant-test-key"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(content().string(Matchers.containsString("\"type\":\"image\"")))
                .andExpect(content().string(Matchers.containsString(
                        java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}))))
                .andRespond(withSuccess(canned(oneMed), MediaType.APPLICATION_JSON));

        List<VisionMedRaw> meds = client.extract(new byte[]{1, 2, 3}, "image/png");

        assertThat(meds).hasSize(1);
        assertThat(meds.get(0).name()).isEqualTo("Pantocid");
    }
}
