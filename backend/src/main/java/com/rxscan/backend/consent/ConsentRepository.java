package com.rxscan.backend.consent;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

/** Append-only: withdrawal is a new row with granted=false — never an UPDATE (DPDP audit trail). */
@Repository
public class ConsentRepository {

    private final JdbcClient jdbc;

    public ConsentRepository(@Qualifier("consumerJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long userId, String purpose, boolean granted, OffsetDateTime grantedAt) {
        jdbc.sql("""
                INSERT INTO user_consent (user_id, purpose, granted, granted_at)
                VALUES (:uid, :purpose, :granted, :grantedAt)""")
                .param("uid", userId).param("purpose", purpose)
                .param("granted", granted).param("grantedAt", grantedAt)
                .update();
    }
}
