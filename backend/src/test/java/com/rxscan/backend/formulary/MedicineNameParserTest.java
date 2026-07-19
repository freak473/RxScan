package com.rxscan.backend.formulary;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Cases drawn from real rows of Extensive_A_Z_medicines_dataset_of_India.csv. */
class MedicineNameParserTest {

    @Test
    void normalize_lowercases_depunctuates_and_collapses() {
        assertThat(MedicineNameParser.normalize("Allegra-M Tablet")).isEqualTo("allegra m tablet");
        assertThat(MedicineNameParser.normalize("Ascoril LS Syrup")).isEqualTo("ascoril ls syrup");
        assertThat(MedicineNameParser.normalize("Augmentin 625 Duo Tablet"))
                .isEqualTo("augmentin 625 duo tablet");
        assertThat(MedicineNameParser.normalize("  Extra   spaces  ")).isEqualTo("extra spaces");
        assertThat(MedicineNameParser.normalize(null)).isEqualTo("");
    }

    @Test
    void parseStrength_pulls_unit_bearing_tokens_from_the_name() {
        assertThat(MedicineNameParser.parseStrength("Allegra 120mg Tablet")).isEqualTo("120mg");
        assertThat(MedicineNameParser.parseStrength("Atarax 25mg Tablet")).isEqualTo("25mg");
        assertThat(MedicineNameParser.parseStrength("Allegra 120 mg Tablet")).isEqualTo("120mg");
    }

    @Test
    void parseStrength_is_null_for_bare_numbers_and_no_number() {
        // Bare numbers with no unit are intentionally NULL (rows stay distinct via brand_name).
        assertThat(MedicineNameParser.parseStrength("Augmentin 625 Duo Tablet")).isNull();
        assertThat(MedicineNameParser.parseStrength("Avil 25 Tablet")).isNull();
        assertThat(MedicineNameParser.parseStrength("Ascoril LS Syrup")).isNull();
        assertThat(MedicineNameParser.parseStrength("Anovate Cream")).isNull();
        assertThat(MedicineNameParser.parseStrength(null)).isNull();
    }

    @Test
    void parseStrength_does_not_match_g_inside_a_word() {
        // "Gel" / "gm" must not be read as a strength unit.
        assertThat(MedicineNameParser.parseStrength("Volini Gel")).isNull();
        assertThat(MedicineNameParser.parseStrength("Anovate 20 gm Cream")).isNull();
    }

    @Test
    void parseForm_reads_form_from_the_name() {
        assertThat(MedicineNameParser.parseForm("Dolo 650 Tablet", null)).isEqualTo("Tablet");
        assertThat(MedicineNameParser.parseForm("Ascoril LS Syrup", null)).isEqualTo("Syrup");
        assertThat(MedicineNameParser.parseForm("Anovate Cream", null)).isEqualTo("Cream");
        assertThat(MedicineNameParser.parseForm("Augmentin Duo Oral Suspension", null))
                .isEqualTo("Oral Suspension");
    }

    @Test
    void parseForm_falls_back_to_pack_size_label() {
        assertThat(MedicineNameParser.parseForm("Mystery Brand", "strip of 10 tablets"))
                .isEqualTo("Tablet");
        assertThat(MedicineNameParser.parseForm("Mystery Brand", "bottle of 100 ml Syrup"))
                .isEqualTo("Syrup");
    }

    @Test
    void parseForm_is_null_when_nothing_recognised() {
        assertThat(MedicineNameParser.parseForm("Some Kit", null)).isNull();
        assertThat(MedicineNameParser.parseForm(null, null)).isNull();
    }
}
