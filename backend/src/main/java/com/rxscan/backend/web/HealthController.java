package com.rxscan.backend.web;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Proves the database is reachable — the foundation slice's acceptance check. */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("database", ping());
    }

    private String ping() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return "up";
        } catch (Exception e) {
            return "down: " + e.getMessage();
        }
    }
}
