package com.rxscan.backend.formulary;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-seeding bulk loader for the formulary catalogue.
 *
 * <p>Runs on every boot but is a no-op once {@code formulary_sku} has rows: it only imports the CSV
 * when the table is <em>empty</em> (a fresh/reset database). Postgres persists the catalogue across
 * restarts, so a normal boot just does one {@code SELECT count(*)} and returns. The upsert on
 * {@code (name_normalized, manufacturer)} keeps a re-seed idempotent and collapses the CSV's
 * exact-duplicate rows.
 *
 * <p>If the table is empty but the CSV isn't present, seeding is skipped with a WARN — the app
 * still starts (formulary matching simply returns no candidates until the catalogue is loaded).
 *
 * <p>Excluded from the {@code test} profile so integration tests never import 256k rows into their
 * Testcontainers database.
 *
 * <p>Only name + manufacturer + parsed strength/form + discontinued flag are stored — no
 * ingredients, indications, or substitutions (non-advisory invariant). This is a matching aid.
 *
 * <p>CSV path defaults to {@code Extensive_A_Z_medicines_dataset_of_India.csv} at the repo root and
 * is searched relative to the working directory; override with {@code formulary.load.csv-path}.
 */
@Component
@Profile("!test")
public class FormularyLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FormularyLoader.class);
    private static final int BATCH_SIZE = 1_000;
    private static final String DEFAULT_CSV = "Extensive_A_Z_medicines_dataset_of_India.csv";

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

    private final JdbcTemplate jdbc;
    private final String csvPath;

    public FormularyLoader(JdbcTemplate jdbc,
                           @Value("${formulary.load.csv-path:" + DEFAULT_CSV + "}") String csvPath) {
        this.jdbc = jdbc;
        this.csvPath = csvPath;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Long existing = jdbc.queryForObject("SELECT count(*) FROM formulary_sku", Long.class);
        if (existing != null && existing > 0) {
            log.info("Formulary already populated ({} SKUs) — skipping CSV seed.", existing);
            return;
        }

        Path path = resolveCsv();
        if (path == null) {
            log.warn("formulary_sku is empty but no CSV found (looked for '{}' relative to {}). "
                            + "Formulary matching will return no candidates until seeded — set "
                            + "formulary.load.csv-path to the dataset to auto-seed on next boot.",
                    csvPath, Path.of("").toAbsolutePath());
            return;
        }
        log.info("formulary_sku is empty — seeding from {}", path.toAbsolutePath());
        load(path);
    }

    /**
     * Resolve the CSV across the likely working directories (repo root when run from there, or one
     * level up when the working dir is {@code backend/}). Returns null if nothing readable is found.
     */
    private Path resolveCsv() {
        Path configured = Path.of(csvPath);
        if (Files.isReadable(configured)) {
            return configured;
        }
        String fileName = configured.getFileName().toString();
        for (String base : new String[]{".", "..", "backend/..", "../.."}) {
            Path candidate = Path.of(base, fileName);
            if (Files.isReadable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void load(Path path) throws Exception {
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

        log.info("Formulary seed done: {} rows read, {} upserted, {} skipped (blank name/manufacturer)",
                read, upserted, skipped);
    }

    private long flush(List<Object[]> batch) {
        if (batch.isEmpty()) {
            return 0;
        }
        int[] rows = jdbc.batchUpdate(UPSERT, batch);
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
