package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.FieldConfidence;
import com.rxscan.backend.extraction.parse.VisionMedRaw;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Pure unit tests over {@link OpenAiCompatibleVisionExtractionClient#parseResponse(String)} (no
 * live call) plus {@link MockRestServiceServer} tests for the request shape and error mapping.
 * This client serves both Grok and Kimi, which both speak the OpenAI {@code chat/completions}
 * shape.
 */
class OpenAiCompatibleVisionExtractionClientTest {

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
        // Real OpenAI-compatible responses carry id/object/created/model/usage siblings too;
        // parseResponse must navigate to choices[0].message.content and ignore the rest.
        return """
                {
                  "id": "chatcmpl-abc123",
                  "object": "chat.completion",
                  "choices": [
                    {
                      "index": 0,
                      "message": {"role": "assistant", "content": %s},
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {"prompt_tokens": 123, "completion_tokens": 45}
                }
                """.formatted(jsonQuote(content));
    }

    private static String jsonQuote(String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Test
    void parses_two_medicines_from_a_realistic_openai_shaped_response() {
        List<VisionMedRaw> meds =
                OpenAiCompatibleVisionExtractionClient.parseResponse(canned(MEDICINES_JSON_ARRAY));

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
    void missing_choices_yields_an_empty_list() {
        List<VisionMedRaw> meds = OpenAiCompatibleVisionExtractionClient.parseResponse("{}");
        assertThat(meds).isEmpty();
    }

    @Test
    void empty_choices_array_yields_an_empty_list() {
        List<VisionMedRaw> meds =
                OpenAiCompatibleVisionExtractionClient.parseResponse("{\"choices\": []}");
        assertThat(meds).isEmpty();
    }

    @Test
    void missing_message_content_yields_an_empty_list() {
        List<VisionMedRaw> meds = OpenAiCompatibleVisionExtractionClient.parseResponse(
                "{\"choices\": [{\"message\": {}}]}");
        assertThat(meds).isEmpty();
    }

    private static OpenAiCompatibleVisionExtractionClient clientBoundTo(MockServerHolder holder) {
        RestClient.Builder builder = RestClient.builder();
        holder.server = MockRestServiceServer.bindTo(builder).build();
        return new OpenAiCompatibleVisionExtractionClient("k", "grok-2-vision-1212",
                "https://example/v1", builder);
    }

    /** Tiny mutable holder so the test can grab the bound server after the client is built. */
    private static final class MockServerHolder {
        MockRestServiceServer server;
    }

    @Test
    void upstream_429_maps_to_rate_limited_exception() {
        MockServerHolder h = new MockServerHolder();
        OpenAiCompatibleVisionExtractionClient client = clientBoundTo(h);
        h.server.expect(requestTo(Matchers.containsString("/chat/completions")))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"error\":{\"message\":\"rate limited\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.extract(new byte[]{1, 2, 3}, "image/png"))
                .isInstanceOf(VisionRateLimitedException.class);
    }

    @Test
    void upstream_server_error_maps_to_upstream_exception() {
        MockServerHolder h = new MockServerHolder();
        OpenAiCompatibleVisionExtractionClient client = clientBoundTo(h);
        h.server.expect(requestTo(Matchers.containsString("/chat/completions")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("boom"));

        assertThatThrownBy(() -> client.extract(new byte[]{1, 2, 3}, "image/png"))
                .isInstanceOf(VisionUpstreamException.class);
    }

    @Test
    void sends_bearer_auth_header_and_base64_data_url_and_parses_one_medicine() {
        MockServerHolder h = new MockServerHolder();
        OpenAiCompatibleVisionExtractionClient client = clientBoundTo(h);

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

        h.server.expect(requestTo(Matchers.containsString("/chat/completions")))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, Matchers.containsString("Bearer ")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content()
                        .string(Matchers.containsString("data:image/png;base64,")))
                .andRespond(withSuccess(canned(oneMed), MediaType.APPLICATION_JSON));

        List<VisionMedRaw> meds = client.extract(new byte[]{1, 2, 3}, "image/png");

        assertThat(meds).hasSize(1);
        assertThat(meds.get(0).name()).isEqualTo("Pantocid");
    }
}
