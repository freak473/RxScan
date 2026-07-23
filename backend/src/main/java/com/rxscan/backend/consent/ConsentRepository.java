package com.rxscan.backend.consent;

import com.rxscan.backend.auth.ApiException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Set;

/** Append-only: withdrawal is a new row with granted=false — never an UPDATE (DPDP audit trail). */
@Repository
public class ConsentRepository {

    private static final Set<String> VALID_PURPOSES = Set.of("process", "notify", "retain_optin");

    private final JdbcClient jdbc;

    public ConsentRepository(@Qualifier("consumerJdbcClient") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long userId, String purpose, boolean granted, OffsetDateTime grantedAt) {
        if (!VALID_PURPOSES.contains(purpose) || grantedAt == null) {
            throw new ApiException(422, "invalid_consent",
                    "purpose must be process|notify|retain_optin and granted_at is required");
        }
        jdbc.sql("""
                INSERT INTO user_consent (user_id, purpose, granted, granted_at)
                VALUES (:uid, :purpose, :granted, :grantedAt)""")
                .param("uid", userId).param("purpose", purpose)
                .param("granted", granted).param("grantedAt", grantedAt)
                .update();
    }
}
