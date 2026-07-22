package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.FieldConfidence;
import com.rxscan.backend.extraction.parse.VisionMedRaw;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared tolerant medicines-array parser used by every
 * {@link VisionExtractionClient} implementation (Gemini, and the OpenAI-compatible providers).
 */
class VisionResponseSupportTest {

    private static final String ONE_MED_ARRAY = """
            [
              {
                "name": "Augmentin 625 Duo",
                "strength": "625mg",
                "doseNotation": "1-0-1",
                "duration": "5 days",
                "meal": "after food",
                "confidence": {"name": 0.95, "strength": 0.9, "doseNotation": 0.92, "duration": 0.88, "meal": 0.8}
              }
            ]
            """;

    @Test
    void parses_a_bare_json_array() {
        List<VisionMedRaw> meds = VisionResponseSupport.parseMedicines(ONE_MED_ARRAY);

        assertThat(meds).hasSize(1);
        VisionMedRaw med = meds.get(0);
        assertThat(med.name()).isEqualTo("Augmentin 625 Duo");
        assertThat(med.strength()).isEqualTo("625mg");
        assertThat(med.doseNotation()).isEqualTo("1-0-1");
        assertThat(med.duration()).isEqualTo("5 days");
        assertThat(med.meal()).isEqualTo("after food");
        assertThat(med.confidence()).isEqualTo(new FieldConfidence(0.95, 0.9, 0.92, 0.88, 0.8));
    }

    @Test
    void parses_a_medicines_wrapped_object() {
        String wrapped = "{\"medicines\": " + ONE_MED_ARRAY + "}";

        List<VisionMedRaw> meds = VisionResponseSupport.parseMedicines(wrapped);

        assertThat(meds).hasSize(1);
        assertThat(meds.get(0).name()).isEqualTo("Augmentin 625 Duo");
    }

    @Test
    void strips_a_json_code_fence_before_parsing() {
        String fenced = "```json\n" + ONE_MED_ARRAY + "\n```";

        List<VisionMedRaw> meds = VisionResponseSupport.parseMedicines(fenced);

        assertThat(meds).hasSize(1);
        assertThat(meds.get(0).name()).isEqualTo("Augmentin 625 Duo");
    }

    @Test
    void strips_a_bare_code_fence_before_parsing() {
        String fenced = "```\n" + ONE_MED_ARRAY + "\n```";

        List<VisionMedRaw> meds = VisionResponseSupport.parseMedicines(fenced);

        assertThat(meds).hasSize(1);
        assertThat(meds.get(0).name()).isEqualTo("Augmentin 625 Duo");
    }

    @Test
    void junk_input_yields_an_empty_list() {
        assertThat(VisionResponseSupport.parseMedicines("not json at all")).isEmpty();
        assertThat(VisionResponseSupport.parseMedicines("{\"foo\": \"bar\"}")).isEmpty();
        assertThat(VisionResponseSupport.parseMedicines("")).isEmpty();
    }
}
