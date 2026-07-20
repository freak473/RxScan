package com.rxscan.backend.formulary;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One-off bulk loader for the engine-plane formulary catalogue.
 *
 * <p>Reads {@code Extensive_A_Z_medicines_dataset_of_India.csv} and upserts each row into
 * {@code formulary_sku}. Gated behind {@code formulary.load.enabled=true} so it never runs on a
 * normal boot; the upsert on {@code (name_normalized, manufacturer)} makes re-running safe and
 * collapses the CSV's exact-duplicate rows.
 *
 * <p>Only name + manufacturer + parsed strength/form + discontinued flag are stored — no
 * ingredients, indications, or substitutions (non-advisory invariant). This is a matching aid.
 *
 * <p>Run once with, e.g.:
 * <pre>./mvnw spring-boot:run -Dspring-boot.run.arguments="\
 *   --formulary.load.enabled=true \
 *   --formulary.load.csv-path=../Extensive_A_Z_medicines_dataset_of_India.csv"</pre>
 */
@Component
@ConditionalOnProperty(name = "formulary.load.enabled", havingValue = "true")
public class FormularyLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FormularyLoader.class);
    private static final int BATCH_SIZE = 1_000;

    private static final String UPSERT = """
            INSERT INTO formulary_sku
                (brand_name, manufacturer, strength, form, is_discontinued, name_normalized)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (name_normalized, manufacturer) DO UPDATE SET
                brand_name      = EXCLUDED.brand_name,
                strength        = EXCLUDED.strength,
                form            = EXCLUDED.form,
                is_discontinued = EXCLUDED.is_discontinued,
                updated_at      = now()
            """;

    private final JdbcTemplate engineJdbc;
    private final String csvPath;

    public FormularyLoader(@Qualifier("engineJdbc") JdbcTemplate engineJdbc,
                           @Value("${formulary.load.csv-path}") String csvPath) {
        this.engineJdbc = engineJdbc;
        this.csvPath = csvPath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path path = Path.of(csvPath);
        log.info("Formulary load starting from {}", path.toAbsolutePath());

        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build();

        long read = 0, upserted = 0, skipped = 0;
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
             CSVParser parser = CSVParser.parse(reader, format)) {

            for (CSVRecord rec : parser) {
                read++;
                String brandName = rec.get("name");
                String manufacturer = rec.get("manufacturer_name");

                // brand_name and manufacturer are NOT NULL and form the dedup key — skip blanks.
                if (isBlank(brandName) || isBlank(manufacturer)) {
                    skipped++;
                    continue;
                }
                String normalized = MedicineNameParser.normalize(brandName);
                if (normalized.isEmpty()) {
                    skipped++;
                    continue;
                }

                batch.add(new Object[]{
                        brandName,
                        manufacturer,
                        MedicineNameParser.parseStrength(brandName),
                        MedicineNameParser.parseForm(brandName, rec.isMapped("pack_size_label")
                                ? rec.get("pack_size_label") : null),
                        isDiscontinued(rec),
                        normalized
                });

                if (batch.size() >= BATCH_SIZE) {
                    upserted += flush(batch);
                    batch.clear();
                    if (read % 50_000 == 0) {
                        log.info("… {} rows read, {} upserted", read, upserted);
                    }
                }
            }
            upserted += flush(batch);
        }

        log.info("Formulary load done: {} rows read, {} upserted, {} skipped (blank name/manufacturer)",
                read, upserted, skipped);
    }

    private long flush(List<Object[]> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        int[] rows = engineJdbc.batchUpdate(UPSERT, batch);
        return rows.length;
    }

    private static boolean isDiscontinued(CSVRecord rec) {
        if (!rec.isMapped("Is_discontinued")) {
            return false;
        }
        return "true".equalsIgnoreCase(rec.get("Is_discontinued").trim());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
