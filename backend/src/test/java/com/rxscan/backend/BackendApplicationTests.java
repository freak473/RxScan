package com.rxscan.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full context against a real Postgres. The two-plane setup uses two separate
 * databases (engine + consumer) so each Flyway instance owns its own history table; a shared
 * database would collide. The migrations are Postgres-specific (pg_trgm, GIN, IDENTITY), so an
 * embedded substitute cannot stand in — we start postgres:16 via Testcontainers.
 *
 * <p>The container is started (and both databases created) in a static block so they exist
 * before {@code @DynamicPropertySource} hands the URLs to the datasources and Flyway connects.
 */
@SpringBootTest
class BackendApplicationTests {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16").withUsername("postgres").withPassword("test");

    static {
        POSTGRES.start();
        try (Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "postgres", "test");
             Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE rxscan_engine");
            s.execute("CREATE DATABASE rxscan_consumer");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create test databases", e);
        }
    }

    @DynamicPropertySource
    static void datasources(DynamicPropertyRegistry registry) {
        String base = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/";
        for (String plane : new String[]{"engine", "consumer"}) {
            registry.add("app.datasource." + plane + ".jdbc-url", () -> base + "rxscan_" + plane);
            registry.add("app.datasource." + plane + ".username", () -> "postgres");
            registry.add("app.datasource." + plane + ".password", () -> "test");
        }
    }

    @Autowired
    @Qualifier("engineJdbc")
    JdbcTemplate engineJdbc;

    @Test
    void contextLoads() {
        // Context loading proves both datasources connect and both Flyway migrations apply.
        // Smoke-check that the engine migration actually created the formulary catalogue.
        Integer tables = engineJdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'formulary_sku'",
                Integer.class);
        assertThat(tables).isEqualTo(1);
    }
}
