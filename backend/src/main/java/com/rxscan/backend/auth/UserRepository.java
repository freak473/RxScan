package com.rxscan.backend.auth;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class UserRepository {

    /** user_id is internal-only (sequential — CLAUDE.md); publicId is the only id a client sees. */
    public record UserRow(long userId, UUID publicId, byte[] dekWrapped) {}

    private static final RowMapper<UserRow> ROW = (rs, n) -> new UserRow(
            rs.getLong("user_id"), rs.getObject("public_id", UUID.class), rs.getBytes("dek_wrapped"));

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findByBlindIndex(byte[] blindIdx) {
        return jdbc.sql("SELECT user_id, public_id, dek_wrapped FROM users WHERE phone_blind_idx = :idx")
                .param("idx", blindIdx).query(ROW).optional();
    }

    public Optional<UserRow> findByPublicId(UUID publicId) {
        return jdbc.sql("SELECT user_id, public_id, dek_wrapped FROM users WHERE public_id = :pid")
                .param("pid", publicId).query(ROW).optional();
    }

    /** Empty when a concurrent verify won the race on phone_blind_idx first (caller re-reads). */
    public Optional<UserRow> tryCreate(byte[] phoneEnc, byte[] blindIdx, byte[] dekWrapped) {
        return jdbc.sql("""
                        INSERT INTO users (phone_enc, phone_blind_idx, dek_wrapped)
                        VALUES (:phone, :idx, :dek)
                        ON CONFLICT (phone_blind_idx) DO NOTHING
                        RETURNING user_id, public_id, dek_wrapped""")
                .param("phone", phoneEnc).param("idx", blindIdx).param("dek", dekWrapped)
                .query(ROW).optional();
    }
}
