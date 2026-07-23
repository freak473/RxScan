package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.FormularyMatcher;
import com.rxscan.backend.extraction.parse.MedicationParser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

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

    // --- rxscan.vision.provider values ---
    private static final String PROVIDER_GEMINI = "gemini";
    private static final String PROVIDER_GROK = "grok";
    private static final String PROVIDER_KIMI = "kimi";
    private static final String PROVIDER_OPENAI = "openai";
    private static final String PROVIDER_CLAUDE = "claude";
    private static final String PROVIDER_ANTHROPIC = "anthropic";
    private static final String PROVIDER_PROPERTY = "${rxscan.vision.provider:" + PROVIDER_GEMINI + "}";

    // Formulary matching runs against formulary_sku (db/migration/V2__formulary.sql), populated
    // once via FormularyLoader (formulary.load.enabled=true). Single-DB v1: the plain JdbcTemplate
    // bean IS the formulary datasource. If the table is empty the trigram query simply returns no
    // candidates — FormularyMatch.NONE — so matching degrades gracefully until the CSV is loaded.
    @Bean
    FormularyMatcher formularyMatcher(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return new FormularyMatcher(jdbcTemplate);
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
            @Value(BASE_URL_PROPERTY) String baseUrl,
            @Value(PROVIDER_PROPERTY) String provider) {
        // RestClient.Builder is not an auto-configured bean in this modular Boot setup, so build
        // one here. The client keeps a builder-taking constructor purely for test injection.
        return switch (provider.toLowerCase()) {
            case PROVIDER_GROK, PROVIDER_KIMI, PROVIDER_OPENAI ->
                    new OpenAiCompatibleVisionExtractionClient(apiKey, model, baseUrl, RestClient.builder());
            case PROVIDER_CLAUDE, PROVIDER_ANTHROPIC ->
                    new ClaudeVisionExtractionClient(apiKey, model, baseUrl, RestClient.builder());
            default -> new GeminiVisionExtractionClient(apiKey, model, baseUrl, RestClient.builder());
        };
    }

    @Bean
    ExtractionService extractionService(ObjectProvider<VisionExtractionClient> clientProvider,
                                        MedicationParser parser) {
        return new ExtractionService(clientProvider.getIfAvailable(), parser);
    }
}
