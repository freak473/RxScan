package com.rxscan.backend.auth;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;

/**
 * HS256 session tokens, 30-day expiry, no refresh tokens in slice A (a 401 routes
 * the app back to signin). sub is ALWAYS users.public_id — never the sequential
 * user_id (CLAUDE.md: sequential ids are internal-only).
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;
    private final Clock clock;

    public JwtService(@Value("${rxscan.auth.jwt-secret}") String secret,
                      @Value("${rxscan.auth.token-ttl-days}") int ttlDays,
                      Clock clock) {
        try {
            // SHA-256-derive so any secret string yields a valid 256-bit HS256 key.
            this.key = new SecretKeySpec(
                    MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)),
                    "HmacSHA256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        this.ttl = Duration.ofDays(ttlDays);
        this.clock = clock;
    }

    public String mint(String publicId) {
        return Jwts.builder()
                .subject(publicId)
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(ttl)))
                .signWith(key)
                .compact();
    }

    public Optional<String> verify(String token) {
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload().getSubject());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
