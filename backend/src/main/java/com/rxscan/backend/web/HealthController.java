package com.rxscan.backend.web;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Proves both databases are reachable — the foundation slice's acceptance check. */
@RestController
public class HealthController {

    private final JdbcTemplate engine;
    private final JdbcTemplate consumer;

    HealthController(@Qualifier("engineJdbc") JdbcTemplate engine,
                    @Qualifier("consumerJdbc") JdbcTemplate consumer) {
        this.engine = engine;
        this.consumer = consumer;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of(
                "engine", ping(engine),
                "consumer", ping(consumer)
        );
    }

    private String ping(JdbcTemplate db) {
        try {
            db.queryForObject("SELECT 1", Integer.class);
            return "up";
        } catch (Exception e) {
            return "down: " + e.getMessage();
        }
    }
}
