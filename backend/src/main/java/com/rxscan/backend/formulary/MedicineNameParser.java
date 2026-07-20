package com.rxscan.backend.formulary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text derivations for the formulary loader — no I/O, no state.
 *
 * <p>Strength and dosage form are not standalone columns in the source CSV; they live inside
 * the printed brand name (e.g. {@code "Allegra 120mg Tablet"}). We parse them out of the name
 * only, never from the composition/ingredient columns (dropped to keep the catalogue
 * non-advisory). See docs/superpowers/specs/2026-07-19-formulary-sku-schema-design.md.
 */
public final class MedicineNameParser {

    private MedicineNameParser() {
    }

    // Unit-bearing strength tokens: a number immediately followed by a recognised unit.
    // Longer units first so "mcg" wins over "g". The trailing lookahead stops "g" from
    // matching the start of a word ("gm", "gel"). Bare numbers with no unit (Dolo 650,
    // Avil 25) intentionally yield NULL — those rows stay distinct via brand_name anyway.
    private static final Pattern STRENGTH =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(mcg|mg|ml|iu|g|%)(?![a-z])",
                    Pattern.CASE_INSENSITIVE);

    // Canonical dosage forms, most-specific first (so "oral suspension" beats "suspension").
    // Keyword is matched as a whole word, case-insensitive, against the brand name then the
    // pack-size label.
    private static final Map<Pattern, String> FORMS = new LinkedHashMap<>();

    static {
        form("oral\\s+suspension", "Oral Suspension");
        form("dry\\s+syrup", "Dry Syrup");
        form("suspension", "Suspension");
        form("tablets?", "Tablet");
        form("capsules?", "Capsule");
        form("syrup", "Syrup");
        form("injections?", "Injection");
        form("infusion", "Infusion");
        form("creams?", "Cream");
        form("ointments?", "Ointment");
        form("gels?", "Gel");
        form("drops?", "Drops");
        form("solution", "Solution");
        form("inhalers?", "Inhaler");
        form("respules?", "Respules");
        form("rotacaps?", "Rotacaps");
        form("sprays?", "Spray");
        form("lotions?", "Lotion");
        form("powders?", "Powder");
        form("sachets?", "Sachet");
        form("lozenges?", "Lozenge");
        form("suppositor(?:y|ies)", "Suppository");
        form("soaps?", "Soap");
        form("shampoos?", "Shampoo");
    }

    private static void form(String keywordRegex, String canonical) {
        FORMS.put(Pattern.compile("\\b" + keywordRegex + "\\b", Pattern.CASE_INSENSITIVE), canonical);
    }

    /**
     * The fuzzy-match key: lowercased, punctuation replaced with spaces, whitespace collapsed.
     * Used for the trigram index and the {@code (name_normalized, manufacturer)} dedup key.
     */
    public static String normalize(String brandName) {
        if (brandName == null) {
            return "";
        }
        return brandName.toLowerCase()
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    /**
     * Strength token(s) parsed from the brand name, multiples joined with {@code +}
     * (e.g. a name carrying both "500mg" and "125mg" → {@code "500mg+125mg"}).
     * Returns {@code null} when the name carries no unit-bearing strength.
     */
    public static String parseStrength(String brandName) {
        if (brandName == null) {
            return null;
        }
        Matcher m = STRENGTH.matcher(brandName);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            if (out.length() > 0) {
                out.append('+');
            }
            // group(1)=number, group(2)=unit — normalised to no internal space ("120 mg" -> "120mg").
            out.append(m.group(1)).append(m.group(2).toLowerCase());
        }
        return out.length() == 0 ? null : out.toString();
    }

    /**
     * Canonical dosage form from the brand name, falling back to the pack-size label.
     * Returns {@code null} when neither names a recognised form.
     */
    public static String parseForm(String brandName, String packSizeLabel) {
        String hit = matchForm(brandName);
        if (hit != null) {
            return hit;
        }
        return matchForm(packSizeLabel);
    }

    private static String matchForm(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Map.Entry<Pattern, String> e : FORMS.entrySet()) {
            if (e.getKey().matcher(text).find()) {
                return e.getValue();
            }
        }
        return null;
    }
}
