package com.rxscan.backend.auth;

import com.rxscan.backend.ConsumerApiTestBase;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerIT extends ConsumerApiTestBase {

    @Test
    void otpRequestValidatesPhone() throws Exception {
        mvc.perform(post("/v1/auth/otp/request").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"9876543210\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/auth/otp/request").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"12345\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("invalid_phone"));
    }

    @Test
    void wrongOtpIs401() throws Exception {
        mvc.perform(post("/v1/auth/otp/verify").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"9876543210\",\"otp\":\"999999\",\"consents\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_otp"));
    }

    @Test
    void verifyCreatesUserOnceStoresConsentsAndMintsOpaqueSub() throws Exception {
        String body = mvc.perform(post("/v1/auth/otp/verify").contentType(APPLICATION_JSON)
                        .content("""
                                {"phone":"9876500001","otp":"000000","consents":[
                                  {"purpose":"process","granted":true,"granted_at":"2026-07-23T10:00:00+05:30"},
                                  {"purpose":"retain_optin","granted":false,"granted_at":"2026-07-23T10:00:01+05:30"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_created").value(true))
                .andReturn().getResponse().getContentAsString();

        // Same phone again: existing user, consents appended not replaced.
        mvc.perform(post("/v1/auth/otp/verify").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"9876500001\",\"otp\":\"000000\",\"consents\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_created").value(false));

        Integer users = consumerJdbc.queryForObject(
                "SELECT count(*) FROM users WHERE phone_blind_idx IS NOT NULL", Integer.class);
        Integer consents = consumerJdbc.queryForObject(
                "SELECT count(*) FROM user_consent", Integer.class);
        assertThat(users).isEqualTo(1);
        assertThat(consents).isEqualTo(2);

        // No plaintext phone at rest.
        byte[] phoneEnc = consumerJdbc.queryForObject("SELECT phone_enc FROM users LIMIT 1", byte[].class);
        assertThat(new String(phoneEnc, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("9876500001");

        // CLAUDE.md rule: JWT sub is the opaque public_id (UUID), never the sequential user_id.
        String token = com.jayway.jsonpath.JsonPath.read(body, "$.token");
        String claims = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(claims).matches(".*\"sub\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\".*");
    }

    @Test
    void invalidConsentPurposeIs422() throws Exception {
        mvc.perform(post("/v1/auth/otp/verify").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"9876500009\",\"otp\":\"000000\",\"consents\":[{\"purpose\":\"marketing\",\"granted\":true,\"granted_at\":\"2026-07-23T10:00:00+05:30\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("invalid_consent"));
    }
}
