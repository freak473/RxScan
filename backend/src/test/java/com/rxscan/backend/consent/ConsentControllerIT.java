package com.rxscan.backend.consent;

import com.rxscan.backend.ConsumerApiTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConsentControllerIT extends ConsumerApiTestBase {

    @Test
    void appendsConsentRowsForTheAuthedUser() throws Exception {
        String token = signIn("9876500002");
        Integer before = consumerJdbc.queryForObject("SELECT count(*) FROM user_consent", Integer.class);

        mvc.perform(put("/v1/me/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"consents\":[{\"purpose\":\"notify\",\"granted\":true,\"granted_at\":\"2026-07-23T11:00:00+05:30\"}]}"))
                .andExpect(status().isNoContent());

        Integer after = consumerJdbc.queryForObject("SELECT count(*) FROM user_consent", Integer.class);
        assertThat(after - before).isEqualTo(1);
    }

    @Test
    void requiresAuth() throws Exception {
        mvc.perform(put("/v1/me/consents").contentType(APPLICATION_JSON)
                        .content("{\"consents\":[]}"))
                .andExpect(status().isUnauthorized());
    }
}
