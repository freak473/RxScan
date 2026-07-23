package com.rxscan.backend.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    JwtService jwt = new JwtService("test-jwt-secret", 30, Clock.systemUTC());

    @Test
    void mintVerifyRoundTrip() {
        String token = jwt.mint("3f6c1a2e-0000-4000-8000-000000000001");
        assertThat(jwt.verify(token)).contains("3f6c1a2e-0000-4000-8000-000000000001");
    }

    @Test
    void garbageAndTamperedTokensAreRejected() {
        assertThat(jwt.verify("not-a-jwt")).isEmpty();
        String token = jwt.mint("abc");
        assertThat(jwt.verify(token + "x")).isEmpty();
        assertThat(new JwtService("other-secret", 30, Clock.systemUTC()).verify(token)).isEmpty();
    }

    @Test
    void expiredTokenIsRejected() {
        Clock past = Clock.fixed(Instant.now().minusSeconds(31L * 24 * 3600), ZoneOffset.UTC);
        String old = new JwtService("test-jwt-secret", 30, past).mint("abc");
        assertThat(jwt.verify(old)).isEmpty();
    }
}
