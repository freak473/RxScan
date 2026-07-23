package com.rxscan.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full context against a real Postgres. One database now (users-only v1;
 * platformisation deferred — see CLAUDE.md); the migrations are Postgres-specific (pgcrypto,
 * IDENTITY), so an embedded substitute cannot stand in — we start postgres:16 via Testcontainers.
 *
 * <p>The container is started in a static block so it exists before {@code @DynamicPropertySource}
 * hands the URL to the datasource and Flyway connects.
 */
@SpringBootTest
@ActiveProfiles("test")   // excludes FormularyLoader — no 256k-row CSV seed into the test container
class BackendApplicationTests {

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
    JdbcTemplate jdbcTemplate;

    @Test
    void contextLoads() {
        // Context loading proves the datasource connects and the Flyway migration applied.
        // Smoke-check that V1 actually created the users table.
        Integer tables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'users'",
                Integer.class);
        assertThat(tables).isEqualTo(1);
    }
}
