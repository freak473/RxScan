package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.FormularyMatcher;
import com.rxscan.backend.extraction.parse.MedicationParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wires the extraction feature together. {@link #visionExtractionClient} is the one optional
 * link: deployments with no vision API key configured still boot and pass
 * {@code BackendApplicationTests} — {@link ExtractionService} simply receives a {@code null}
 * client and fails loudly (503, {@link VisionUnavailableException}) only if/when a caller actually
 * asks it to extract.
 *
 * <p><b>Deviation from a plain {@code @ConditionalOnProperty}:</b> that annotation only checks
 * whether the property key is <em>present</em> — an empty-string value (which is what
 * {@code rxscan.vision.api-key} resolves to when neither it nor {@code GEMINI_API_KEY} is set)
 * would still count as "present" and wrongly create the bean. {@link ConditionalOnExpression} with
 * an explicit {@code hasText(...)} check is used instead so "no key configured" reliably means "no
 * bean", in both directions (property set, env var set, or neither).
 */
@Configuration
public class ExtractionConfig {

    // Resolves rxscan.vision.api-key, falling back to the GEMINI_API_KEY env var, else empty.
    private static final String API_KEY_PLACEHOLDER = "${rxscan.vision.api-key:${GEMINI_API_KEY:}}";
    private static final String HAS_API_KEY_EXPRESSION =
            "T(org.springframework.util.StringUtils).hasText('" + API_KEY_PLACEHOLDER + "')";
    private static final String MODEL_PROPERTY = "${rxscan.vision.model}";
    private static final String BASE_URL_PROPERTY = "${rxscan.vision.base-url}";
    private static final String ENGINE_JDBC = "engineJdbc";

    @Bean
    FormularyMatcher formularyMatcher(@Qualifier(ENGINE_JDBC) JdbcTemplate engineJdbc) {
        return new FormularyMatcher(engineJdbc);
    }

    @Bean
    MedicationParser medicationParser(FormularyMatcher formularyMatcher) {
        return new MedicationParser(formularyMatcher);
    }

    @Bean
    @ConditionalOnExpression(HAS_API_KEY_EXPRESSION)
    VisionExtractionClient visionExtractionClient(
            @Value(API_KEY_PLACEHOLDER) String apiKey,
            @Value(MODEL_PROPERTY) String model,
            @Value(BASE_URL_PROPERTY) String baseUrl) {
        return new GeminiVisionExtractionClient(apiKey, model, baseUrl);
    }

    @Bean
    ExtractionService extractionService(ObjectProvider<VisionExtractionClient> clientProvider,
                                        MedicationParser parser) {
        return new ExtractionService(clientProvider.getIfAvailable(), parser);
    }
}
