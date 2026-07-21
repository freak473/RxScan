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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end orchestration over the design's demo meds, asserting the exact flag set per case and
 * the CDSCO invariant that a flag NEVER carries a value. Testcontainers pattern copied from
 * {@code BackendApplicationTests}; formulary seeded like {@code FormularyMatcherTest}.
 */
@SpringBootTest
class MedicationParserTest {

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

    private static List<FlagReason> reasons(MedParseResult r) {
        return r.flags().stream().map(Flag::reason).toList();
    }

    @Test
    void clean_medicine_resolves_and_raises_no_flags() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Augmentin 625 Duo", "625mg", "1-0-1", "5 days", "after food", high()));

        assertThat(r.drug().value()).isEqualTo("Augmentin 625 Duo"); // never rewritten
        assertThat(r.drug().formularyId()).isNotNull();
        assertThat(r.frequency().pattern()).isEqualTo(Pattern.DAILY);
        assertThat(r.frequency().slots()).isEqualTo(new Slots(1, 0, 1, 0));
        assertThat(r.mealTiming().value()).isEqualTo(Meal.AFTER);
        assertThat(r.duration().type()).isEqualTo(DurationType.DAYS);
        assertThat(r.duration().days()).isEqualTo(5);
        assertThat(r.flags()).isEmpty();
    }

    @Test
    void unreadable_strength_flags_strength_unreadable() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Augmentin 625 Duo", null, "1-0-1", "5 days", "after food",
                new FieldConfidence(0.95, 0.30, 0.95, 0.95, 0.95)));
        assertThat(reasons(r)).contains(FlagReason.STRENGTH_UNREADABLE);
    }

    @Test
    void read_strength_conflicting_with_formulary_flags_anomaly() {
        // Pantocid SKU strength is 40mg; the read says 20mg → anomaly (never auto-corrected).
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Pantocid 40mg", "20mg", "1-0-1", "5 days", "before food", high()));
        assertThat(r.strength().value()).isEqualTo("20mg"); // left exactly as read
        assertThat(reasons(r)).contains(FlagReason.STRENGTH_ANOMALY);
    }

    @Test
    void unclear_duration_flags_duration_unclear() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Dolo 650", null, "1-0-1", "a while", null, high()));
        assertThat(r.duration().type()).isEqualTo(DurationType.UNSPECIFIED);
        assertThat(reasons(r)).contains(FlagReason.DURATION_UNCLEAR);
    }

    @Test
    void weekly_frequency_forces_a_confirm() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Dolo 650", null, "weekly", "4 weeks", null, high()));
        assertThat(r.frequency().pattern()).isEqualTo(Pattern.WEEKLY);
        assertThat(reasons(r)).contains(FlagReason.FREQ_NON_DAILY);
    }

    @Test
    void prn_is_not_treated_as_non_daily() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Dolo 650", null, "SOS", null, null, high()));
        assertThat(r.frequency().pattern()).isEqualTo(Pattern.PRN);
        assertThat(reasons(r)).doesNotContain(FlagReason.FREQ_NON_DAILY);
    }

    @Test
    void unrecognized_frequency_is_flagged() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Dolo 650", null, "scribble", "5 days", null, high()));
        assertThat(reasons(r)).contains(FlagReason.FREQ_UNRECOGNIZED);
    }

    @Test
    void unknown_low_confidence_name_flags_name_low_confidence() {
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Xyzzy Zztop", null, "1-0-1", "5 days", null,
                new FieldConfidence(0.40, 0.95, 0.95, 0.95, 0.95)));
        assertThat(r.drug().formularyId()).isNull();
        assertThat(reasons(r)).contains(FlagReason.NAME_LOW_CONFIDENCE);
    }

    @Test
    void absent_meal_timing_uses_slots_only_and_raises_no_meal_flag() {
        // No before/after-food note → meal is null (never inferred) and the schedule stands on the
        // morning/noon/night slots alone. A missing meal note is normal, not an anomaly.
        MedParseResult r = parser.parse(new VisionMedRaw(
                "Augmentin 625 Duo", "625mg", "1-0-1", "5 days", null, high()));
        assertThat(r.mealTiming().value()).isNull();
        assertThat(r.frequency().slots()).isEqualTo(new Slots(1, 0, 1, 0));
        assertThat(r.flags().stream().map(Flag::field)).doesNotContain(FieldName.MEAL);
    }

    @Test
    void a_flag_structurally_cannot_carry_a_value() {
        // CDSCO "flag, don't correct": Flag has exactly {field, reason} — no value component.
        var components = Flag.class.getRecordComponents();
        assertThat(components).hasSize(2);
        assertThat(java.util.Arrays.stream(components).map(java.lang.reflect.RecordComponent::getName))
                .containsExactlyInAnyOrder("field", "reason");
    }
}
