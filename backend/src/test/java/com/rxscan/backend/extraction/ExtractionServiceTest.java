package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.FieldConfidence;
import com.rxscan.backend.extraction.parse.FlagReason;
import com.rxscan.backend.extraction.parse.FormularyMatcher;
import com.rxscan.backend.extraction.parse.MedParseResult;
import com.rxscan.backend.extraction.parse.MedicationParser;
import com.rxscan.backend.extraction.parse.Pattern;
import com.rxscan.backend.extraction.parse.VisionMedRaw;
import com.rxscan.backend.formulary.MedicineNameParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExtractionService} orchestration: a stub vision client feeds raw reads through the real
 * {@link MedicationParser}/{@link FormularyMatcher} against a seeded formulary. Testcontainers
 * pattern + seeding copied from {@code MedicationParserTest}.
 */
@SpringBootTest
class ExtractionServiceTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUsername("postgres").withPassword("test");

    static {
        POSTGRES.start();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "test");
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE rxscan_engine");
            s.execute("CREATE DATABASE rxscan_consumer");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create test databases", e);
        }
    }

    @DynamicPropertySource
    static void datasources(DynamicPropertyRegistry registry) {
        String base = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/";
        for (String plane : new String[]{"engine", "consumer"}) {
            registry.add("app.datasource." + plane + ".jdbc-url", () -> base + "rxscan_" + plane);
            registry.add("app.datasource." + plane + ".username", () -> "postgres");
            registry.add("app.datasource." + plane + ".password", () -> "test");
        }
    }

    @Autowired
    @Qualifier("engineJdbc")
    JdbcTemplate engineJdbc;

    private MedicationParser parser;

    @BeforeEach
    void seed() {
        engineJdbc.update("DELETE FROM formulary_sku");
        insert("Augmentin 625 Duo Tablet", "GSK", null);
        insert("Pantocid 40mg Tablet", "Sun Pharma", "40mg");
        insert("Dolo 650 Tablet", "Micro Labs", null);
        parser = new MedicationParser(new FormularyMatcher(engineJdbc));
    }

    private void insert(String brandName, String mfr, String strength) {
        engineJdbc.update(
                "INSERT INTO formulary_sku (brand_name, manufacturer, strength, form, name_normalized) "
                        + "VALUES (?, ?, ?, ?, ?)",
                brandName, mfr, strength, "Tablet", MedicineNameParser.normalize(brandName));
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
        assertThat(augmentin.drug().formularyId()).isNotNull();
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
