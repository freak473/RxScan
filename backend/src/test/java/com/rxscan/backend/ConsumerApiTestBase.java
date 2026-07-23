package com.rxscan.backend;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared base for consumer-plane endpoint tests: one postgres:16 container for the
 * whole test JVM (same static-block pattern as BackendApplicationTests), MockMvc,
 * and a stub-OTP sign-in helper. No live SMS, no live AI — ever.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")   // excludes FormularyLoader — no 256k-row CSV seed into the test container
public abstract class ConsumerApiTestBase {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUsername("postgres").withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.jdbc-url", POSTGRES::getJdbcUrl);
        registry.add("app.datasource.username", () -> "postgres");
        registry.add("app.datasource.password", () -> "test");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate consumerJdbc;

    /** Stub-OTP sign-in (000000); returns the bearer token. */
    protected String signIn(String phone) throws Exception {
        String body = mvc.perform(post("/v1/auth/otp/verify").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"otp\":\"000000\",\"consents\":[]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
