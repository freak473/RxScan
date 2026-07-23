package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.FieldConfidence;
import com.rxscan.backend.extraction.parse.FlagReason;
import com.rxscan.backend.extraction.parse.FormularyMatcher;
import com.rxscan.backend.extraction.parse.MedParseResult;
import com.rxscan.backend.extraction.parse.MedicationParser;
import com.rxscan.backend.extraction.parse.Pattern;
import com.rxscan.backend.extraction.parse.VisionMedRaw;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExtractionService} orchestration: a stub vision client feeds raw reads through the real
 * {@link MedicationParser}. Formulary matching is disabled (the {@code formulary_sku} catalog was
 * dropped with the engine plane — users-only v1, platformisation deferred; see CLAUDE.md), so the
 * parser is built with {@link FormularyMatcher#disabled()} — a plain unit test, no database, no
 * Testcontainers.
 */
class ExtractionServiceTest {

    private MedicationParser parser;

    @BeforeEach
    void setUp() {
        parser = new MedicationParser(FormularyMatcher.disabled());
    }

    private static FieldConfidence high() {
        return new FieldConfidence(0.95, 0.95, 0.95, 0.95, 0.95);
    }

    @Test
    void extract_resolves_a_clean_medicine_and_flags_a_weekly_one() {
        List<VisionMedRaw> stubReads = List.of(
                new VisionMedRaw("Augmentin 625 Duo", "625mg", "1-0-1", "5 days", "after food", high()),
                new VisionMedRaw("Dolo 650", null, "weekly", "4 weeks", null, high()));
        VisionExtractionClient stubClient = (image, mediaType) -> stubReads;

        ExtractionService service = new ExtractionService(stubClient, parser);
        List<MedParseResult> results = service.extract(new byte[]{1, 2, 3}, "image/jpeg");

        assertThat(results).hasSize(2);

        MedParseResult augmentin = results.get(0);
        assertThat(augmentin.drug().value()).isEqualTo("Augmentin 625 Duo");
        assertThat(augmentin.drug().formularyId()).isNull(); // formulary matching disabled
        assertThat(augmentin.flags()).isEmpty();

        MedParseResult dolo = results.get(1);
        assertThat(dolo.frequency().pattern()).isEqualTo(Pattern.WEEKLY);
        assertThat(dolo.flags().stream().map(f -> f.reason())).contains(FlagReason.FREQ_NON_DAILY);
    }

    @Test
    void extract_throws_vision_unavailable_when_no_client_is_configured() {
        ExtractionService service = new ExtractionService(null, parser);

        assertThatThrownBy(() -> service.extract(new byte[]{1, 2, 3}, "image/jpeg"))
                .isInstanceOf(VisionUnavailableException.class);
    }
}
