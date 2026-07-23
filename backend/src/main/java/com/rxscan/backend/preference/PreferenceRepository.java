package com.rxscan.backend.preference;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class PreferenceRepository {

    public record PreferenceRow(byte[] payloadEnc, OffsetDateTime updatedAt) {}

    private final JdbcClient jdbc;

    public PreferenceRepository(@Qualifier("consumerJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(long userId, byte[] payloadEnc) {
        jdbc.sql("""
                INSERT INTO user_preference (user_id, payload_enc) VALUES (:uid, :payload)
                ON CONFLICT (user_id) DO UPDATE SET payload_enc = EXCLUDED.payload_enc""")
                .param("uid", userId).param("payload", payloadEnc)
                .update();
    }

    public Optional<PreferenceRow> find(long userId) {
        return jdbc.sql("SELECT payload_enc, updated_at FROM user_preference WHERE user_id = :uid")
                .param("uid", userId)
                .query((rs, n) -> new PreferenceRow(
                        rs.getBytes("payload_enc"),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .optional();
    }
}
