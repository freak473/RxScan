package com.rxscan.backend.prescription;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PrescriptionRepository {

    public record Created(UUID rxId, OffsetDateTime updatedAt) {}
    public record RxRow(UUID rxId, byte[] payloadEnc, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

    private static final RowMapper<RxRow> ROW = (rs, n) -> new RxRow(
            rs.getObject("rx_id", UUID.class), rs.getBytes("payload_enc"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));

    private final JdbcClient jdbc;

    public PrescriptionRepository(@Qualifier("consumerJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Created insert(long userId, byte[] payloadEnc) {
        return jdbc.sql("""
                        INSERT INTO prescription (user_id, payload_enc) VALUES (:uid, :payload)
                        RETURNING rx_id, updated_at""")
                .param("uid", userId).param("payload", payloadEnc)
                .query((rs, n) -> new Created(rs.getObject("rx_id", UUID.class),
                        rs.getObject("updated_at", OffsetDateTime.class)))
                .single();
    }

    /** Ownership enforced in the WHERE clause: someone else's rx_id behaves exactly like a missing one. */
    public Optional<OffsetDateTime> update(UUID rxId, long userId, byte[] payloadEnc) {
        return jdbc.sql("""
                        UPDATE prescription SET payload_enc = :payload
                        WHERE rx_id = :rx AND user_id = :uid
                        RETURNING updated_at""")
                .param("payload", payloadEnc).param("rx", rxId).param("uid", userId)
                .query((rs, n) -> rs.getObject("updated_at", OffsetDateTime.class))
                .optional();
    }

    public List<RxRow> findSince(long userId, OffsetDateTime since) {
        if (since == null) {
            return jdbc.sql("SELECT rx_id, payload_enc, created_at, updated_at FROM prescription WHERE user_id = :uid ORDER BY created_at")
                    .param("uid", userId).query(ROW).list();
        }
        return jdbc.sql("SELECT rx_id, payload_enc, created_at, updated_at FROM prescription WHERE user_id = :uid AND updated_at > :since ORDER BY created_at")
                .param("uid", userId).param("since", since).query(ROW).list();
    }
}
