package com.rxscan.backend.extraction.parse;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the trigram formulary match against a real Postgres (pg_trgm/GIN come from the engine
 * V1 migration). Seeds a tiny known formulary and asserts exact + near-miss resolve, unknown does
 * not, and the resolved SKU strength is exposed for the orchestrator's strength cross-check.
 * Testcontainers pattern copied from {@code BackendApplicationTests}.
 */
@SpringBootTest
class FormularyMatcherTest {

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

    private FormularyMatcher matcher;

    @BeforeEach
    void seed() {
        engineJdbc.update("DELETE FROM formulary_sku");
        insert("Augmentin 625 Duo Tablet", "GSK", null);
        insert("Pantocid 40mg Tablet", "Sun Pharma", "40mg");
        insert("Dolo 650 Tablet", "Micro Labs", null);
        matcher = new FormularyMatcher(engineJdbc);
    }

    private void insert(String brandName, String mfr, String strength) {
        engineJdbc.update(
                "INSERT INTO formulary_sku (brand_name, manufacturer, strength, form, name_normalized) "
                        + "VALUES (?, ?, ?, ?, ?)",
                brandName, mfr, strength, "Tablet", MedicineNameParser.normalize(brandName));
    }

    @Test
    void exact_name_resolves_formulary_id_above_high_threshold() {
        FormularyMatch m = matcher.match("Augmentin 625 Duo", null);
        assertThat(m.matched()).isTrue();
        assertThat(m.formularyId()).isNotNull();
        assertThat(m.score()).isGreaterThanOrEqualTo(ParserThresholds.MATCH_HIGH);
    }

    @Test
    void typo_still_resolves_above_high_threshold() {
        FormularyMatch m = matcher.match("Augmentn 625 Duo", null);
        assertThat(m.matched()).isTrue();
        assertThat(m.formularyId()).isNotNull();
        assertThat(m.score()).isGreaterThanOrEqualTo(ParserThresholds.MATCH_HIGH);
    }

    @Test
    void unknown_name_does_not_match() {
        FormularyMatch m = matcher.match("Xyzzy Zztop", null);
        assertThat(m.matched()).isFalse();
        assertThat(m.formularyId()).isNull();
    }

    @Test
    void resolved_match_exposes_sku_strength_for_cross_check() {
        FormularyMatch m = matcher.match("Pantocid 40mg", "40mg");
        assertThat(m.matched()).isTrue();
        assertThat(m.skuStrength()).isEqualTo("40mg");
    }
}
