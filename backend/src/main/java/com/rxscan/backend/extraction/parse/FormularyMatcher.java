package com.rxscan.backend.extraction.parse;

import com.rxscan.backend.formulary.MedicineNameParser;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * Fuzzy formulary lookup against {@code rxscan_engine.formulary_sku}. Reuses
 * {@link MedicineNameParser#normalize(String)} for the match key and the existing
 * {@code idx_formulary_name_trgm} GIN index over active SKUs. See
 * docs/superpowers/specs/2026-07-20-rx-deterministic-parser-design.md §Components 2.
 *
 * <p>A plain collaborator (not a Spring bean): constructed with the engine {@link JdbcTemplate} by
 * whoever owns it. Confirming a brand exists raises confidence and resolves a {@code formularyId} —
 * it never edits the displayed name/strength (CLAUDE.md "Never rewrite the read text").
 */
public final class FormularyMatcher {

    // Trigram candidate filter (%) + similarity score over active SKUs only. Key is bound twice:
    // once for the `%` filter, once for the similarity() score. Top 5 by score, we take the best.
    private static final String SQL = """
            SELECT formulary_id, brand_name, strength, similarity(name_normalized, ?::text) AS score
            FROM formulary_sku
            WHERE is_discontinued = false
              AND name_normalized % ?::text
            ORDER BY score DESC
            LIMIT 5
            """;

    private final JdbcTemplate engineJdbc;

    public FormularyMatcher(JdbcTemplate engineJdbc) {
        this.engineJdbc = engineJdbc;
    }

    /**
     * @param name     the medicine name as written (verbatim); normalized only to build the key
     * @param strength the read strength — unused for matching, kept for a symmetric signature
     * @return the best {@link FormularyMatch}; {@link FormularyMatch#NONE} when there is no
     *         trigram candidate at all. {@code matched} is true only when the top score reaches
     *         {@link ParserThresholds#MATCH_HIGH}.
     */
    public FormularyMatch match(String name, String strength) {
        String key = MedicineNameParser.normalize(name);
        if (key.isBlank()) {
            return FormularyMatch.NONE;
        }

        List<Candidate> rows = engineJdbc.query(SQL,
                (rs, i) -> new Candidate(rs.getLong("formulary_id"),
                        rs.getString("strength"),
                        rs.getDouble("score")),
                key, key);

        if (rows.isEmpty()) {
            return FormularyMatch.NONE;
        }
        Candidate best = rows.get(0); // ordered by score DESC in SQL
        boolean matched = best.score() >= ParserThresholds.MATCH_HIGH;
        return new FormularyMatch(
                matched ? best.formularyId() : null,
                best.score(),
                best.strength(),
                matched);
    }

    private record Candidate(long formularyId, String strength, double score) {
    }
}
