package com.rxscan.backend.preference;

import com.rxscan.backend.ConsumerApiTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PreferenceControllerIT extends ConsumerApiTestBase {

    @Test
    void roundTripUpsertAndEncryptionAtRest() throws Exception {
        String token = signIn("9876500003");

        mvc.perform(get("/v1/me/preferences").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        String prefs = "{\"payload\":{\"schema\":1,\"mealTimes\":{\"breakfast\":\"08:00\",\"lunch\":\"13:00\",\"dinner\":\"20:30\"}}}";
        mvc.perform(put("/v1/me/preferences").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(prefs))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/me/preferences").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.mealTimes.breakfast").value("08:00"))
                .andExpect(jsonPath("$.updated_at").exists());

        // Upsert: second PUT replaces, still exactly one row for this user.
        mvc.perform(put("/v1/me/preferences").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"payload\":{\"schema\":1,\"mealTimes\":{\"breakfast\":\"07:30\"}}}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/me/preferences").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.payload.mealTimes.breakfast").value("07:30"));

        // Server-opaque: nothing readable at rest.
        byte[] enc = consumerJdbc.queryForObject(
                "SELECT payload_enc FROM user_preference LIMIT 1", byte[].class);
        assertThat(new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("breakfast");
    }

    @Test
    void oversizePayloadIs413() throws Exception {
        String token = signIn("9876500004");
        String big = "{\"payload\":{\"blob\":\"" + "x".repeat(300 * 1024) + "\"}}";
        mvc.perform(put("/v1/me/preferences").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("payload_too_large"));
    }
}
