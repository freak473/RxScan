package com.rxscan.backend.extraction.parse;

/**
 * Outcome of a formulary trigram lookup.
 *
 * <p>A resolved match ({@code matched == true}) confirms the brand exists as printed and lets the
 * orchestrator raise {@code drug.confidence} — it never rewrites the displayed name (parser spec
 * §Components 2, CLAUDE.md "Never rewrite the read text"). {@code skuStrength} is exposed so the
 * orchestrator can cross-check the read strength; the strength decision itself lives there, not
 * here.
 *
 * @param formularyId resolved catalog id, or {@code null} when not matched
 * @param score       best trigram similarity found (0 when no candidate at all)
 * @param skuStrength strength of the best candidate row (may be {@code null})
 * @param matched     true when {@code score >= MATCH_HIGH}
 */
public record FormularyMatch(Long formularyId, double score, String skuStrength, boolean matched) {

    static final FormularyMatch NONE = new FormularyMatch(null, 0.0, null, false);
}
