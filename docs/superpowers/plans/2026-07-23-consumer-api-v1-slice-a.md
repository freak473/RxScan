# Consumer API v1 — Slice A (Backend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the consumer-plane backend of `docs/superpowers/specs/2026-07-23-consumer-api-v1-design.md`: phone-OTP auth (stub + Gupshup-ready), consents, preferences, prescriptions — plus the FE-facing contract doc.

**Architecture:** New packages `auth/`, `consent/`, `preference/`, `prescription/` under `com.rxscan.backend`, all on the **consumer** datasource via `JdbcClient` (no ORM). App-layer crypto: phone AES-GCM under a master key + HMAC blind index; payloads AES-GCM under a per-user DEK wrapped by the master key. JWT (jjwt, HS256, 30 days) enforced by a plain `HandlerInterceptor` — no spring-security.

**Tech Stack:** Spring Boot 4.1.0 / Java 21, `JdbcClient`, Flyway (edit-V1-in-place dev workflow), jjwt 0.13.0, MockMvc + Testcontainers postgres:16 for integration tests, `MockRestServiceServer` for Gupshup.

**Android FE wiring (AuthApi/MeApi/PrescriptionApi, DataStore, Room, nav) is a separate follow-up plan** — it integrates against this backend once it exists. The FE store design is fully specified in the spec.

## Global Constraints

- **No live AI calls, no live SMS in any test** (CLAUDE.md). Gupshup is tested only via `MockRestServiceServer`; OTP integration tests use the stub path.
- **Sequential `user_id` never leaves the DB layer** (CLAUDE.md): JWT `sub` = `users.public_id` (UUID); no client-visible id, path, or token may carry `user_id`.
- **No `suggested_value` field anywhere** (CDSCO) — no schema in this plan may add one.
- **Server never parses `payload`** — prescriptions/preferences are opaque JSON, encrypted to `payload_enc BYTEA`, round-tripped verbatim.
- Errors are uniform `{"error":{"code":..., "message":...}}`. Codes: `invalid_phone` (422), `invalid_otp` (401), `unauthorized` (401), `not_found` (404), `payload_too_large` (413, cap 256 KB), `invalid_payload` (422), `otp_delivery_failed` (503).
- Consent purposes v1: `process` | `notify` | `retain_optin`. Consents are **append-only rows** — withdrawal is a new row with `granted=false`, never an UPDATE.
- **Build tool:** `./mvnw` is broken on this machine (dead wget dylib) — use system `mvn` (same 3.9.16). Run from `backend/`.
- **Integration tests need Docker running** (Testcontainers postgres:16). Unit tests (crypto/JWT/OTP) don't.
- **Dev DB workflow:** editing `V1__init.sql` requires `dropdb rxscan_consumer && createdb rxscan_consumer` (it's empty/disposable); Flyway re-applies on next boot.
- **CLAUDE.md commit rule:** Task 1 makes a small CLAUDE.md edit that stays **uncommitted** through the middle tasks (pending edits satisfy the post-commit hook); Task 10 finalizes and commits it.
- Wire formats use snake_case (`user_created`, `granted_at`, `rx_id`, `updated_at`) via `@JsonProperty` on the specific DTOs — do **not** set a global Jackson naming strategy (it would break the existing `/extract` contract).

## File Structure

```
backend/src/main/java/com/rxscan/backend/
  auth/           ApiException, ApiExceptionHandler, CryptoService, JwtService,
                  JwtInterceptor, WebConfig, OtpSender, StubOtpSender, GupshupOtpSender,
                  OtpDeliveryException, OtpConfig, OtpService, UserRepository,
                  CurrentUser, PayloadCodec, AuthController
  consent/        ConsentDto, ConsentRepository, ConsentController
  preference/     PreferenceRepository, PreferenceController
  prescription/   PrescriptionRepository, PrescriptionController
backend/src/main/resources/db/migration/consumer/V1__init.sql   (add public_id)
backend/src/main/resources/application.properties               (crypto/auth/otp keys)
backend/src/test/java/com/rxscan/backend/
  auth/           CryptoServiceTest, JwtServiceTest, OtpServiceTest, GupshupOtpSenderTest,
                  AuthControllerIT
  ConsumerApiTestBase.java
  consent/        ConsentControllerIT
  preference/     PreferenceControllerIT
  prescription/   PrescriptionControllerIT
docs/api-contract-v1.md
```

---

### Task 1: `users.public_id` migration

**Files:**
- Modify: `backend/src/main/resources/db/migration/consumer/V1__init.sql` (users table block)
- Modify: `/Users/ankitjain/rxscan/CLAUDE.md` (status line only; leave uncommitted until Task 10)

**Interfaces:**
- Produces: column `users.public_id UUID UNIQUE NOT NULL DEFAULT gen_random_uuid()` — later tasks SELECT/RETURN it.

- [ ] **Step 1: Add the column**

In `V1__init.sql`, inside `CREATE TABLE users`, insert directly under the `user_id` line:

```sql
    public_id         UUID NOT NULL UNIQUE DEFAULT gen_random_uuid(),  -- the ONLY id a client ever sees (JWT sub)
```

- [ ] **Step 2: Recreate the dev consumer DB and verify**

```bash
dropdb rxscan_consumer && createdb rxscan_consumer
cd /Users/ankitjain/rxscan/backend && mvn -q spring-boot:run &   # wait for boot
curl -sf http://localhost:8080/health          # expect {"consumer":"up","engine":"up"}
psql -d rxscan_consumer -tAc "SELECT column_name FROM information_schema.columns WHERE table_name='users' AND column_name='public_id'"
# expect: public_id — then stop the server (pkill -f com.rxscan)
```

- [ ] **Step 3: Mark CLAUDE.md in-progress (do NOT commit)**

In CLAUDE.md "Current status", append to the backend paragraph: `Slice A implementation in progress (docs/superpowers/plans/2026-07-23-consumer-api-v1-slice-a.md).` This stays a pending edit until Task 10.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/consumer/V1__init.sql
git commit -m "backend: add users.public_id (opaque client-facing id, JWT sub)"
```

---

### Task 2: CryptoService

**Files:**
- Create: `backend/src/main/java/com/rxscan/backend/auth/CryptoService.java`
- Modify: `backend/src/main/resources/application.properties` (append key config)
- Test: `backend/src/test/java/com/rxscan/backend/auth/CryptoServiceTest.java`

**Interfaces:**
- Produces: `byte[] blindIndex(String phone)` · `byte[] newDek()` · `byte[] wrapDek(byte[] dek)` · `byte[] unwrapDek(byte[] wrapped)` · `byte[] encrypt(byte[] plain, byte[] key)` · `byte[] decrypt(byte[] blob, byte[] key)` · `byte[] encryptWithMaster(byte[] plain)` · `byte[] decryptWithMaster(byte[] blob)`
- Keys are derived as SHA-256 of the configured secret strings — no base64 footguns; KMS swap later touches only this class.

- [ ] **Step 1: Append config to `application.properties`**

```properties
# Consumer-plane crypto + auth. Dev defaults below are NOT for production —
# override via env. Keys are derived as SHA-256(secret string) in CryptoService.
rxscan.crypto.master-key=${RXSCAN_MASTER_KEY:dev-master-key-not-for-prod}
rxscan.crypto.blind-idx-key=${RXSCAN_BLIND_IDX_KEY:dev-blind-idx-key-not-for-prod}
rxscan.auth.jwt-secret=${RXSCAN_JWT_SECRET:dev-jwt-secret-not-for-prod}
rxscan.auth.token-ttl-days=30
rxscan.auth.dev-otp=000000
# OTP delivery strategy: stub (accepts rxscan.auth.dev-otp, sends nothing) | gupshup.
rxscan.otp.provider=stub
# Gupshup (dormant until DLT contract closes — see CHECKLIST):
# rxscan.otp.gupshup.user-id= / password= / principal-entity-id= / template-id=
# rxscan.otp.gupshup.template=Your RxScan OTP is %s
```

- [ ] **Step 2: Write the failing test**

```java
package com.rxscan.backend.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    CryptoService crypto = new CryptoService("test-master", "test-blind");

    @Test
    void blindIndexIsDeterministicAndKeyed() {
        assertThat(crypto.blindIndex("+919876543210")).isEqualTo(crypto.blindIndex("+919876543210"));
        assertThat(crypto.blindIndex("+919876543210")).isNotEqualTo(crypto.blindIndex("+919876543211"));
        assertThat(new CryptoService("test-master", "other-key").blindIndex("+919876543210"))
                .isNotEqualTo(crypto.blindIndex("+919876543210"));
    }

    @Test
    void dekWrapRoundTrip() {
        byte[] dek = crypto.newDek();
        assertThat(dek).hasSize(32);
        assertThat(crypto.unwrapDek(crypto.wrapDek(dek))).isEqualTo(dek);
    }

    @Test
    void encryptRoundTripAndNonDeterminism() {
        byte[] dek = crypto.newDek();
        byte[] plain = "{\"schema\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] blob = crypto.encrypt(plain, dek);
        assertThat(crypto.decrypt(blob, dek)).isEqualTo(plain);
        assertThat(blob).isNotEqualTo(crypto.encrypt(plain, dek)); // fresh IV each call
        assertThat(new String(blob, StandardCharsets.ISO_8859_1)).doesNotContain("schema");
    }

    @Test
    void tamperedCiphertextFails() {
        byte[] dek = crypto.newDek();
        byte[] blob = crypto.encrypt("secret".getBytes(StandardCharsets.UTF_8), dek);
        blob[blob.length - 1] ^= 1;
        byte[] tampered = blob;
        assertThatThrownBy(() -> crypto.decrypt(tampered, dek)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void masterRoundTrip() {
        byte[] phone = "+919876543210".getBytes(StandardCharsets.UTF_8);
        assertThat(crypto.decryptWithMaster(crypto.encryptWithMaster(phone))).isEqualTo(phone);
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd /Users/ankitjain/rxscan/backend && mvn -q test -Dtest=CryptoServiceTest`
Expected: COMPILATION ERROR — `CryptoService` does not exist.

- [ ] **Step 4: Implement**

```java
package com.rxscan.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * App-layer envelope crypto for the consumer plane (tech design §6.H / §7.1).
 * Phone: AES-GCM under the master key + keyed-HMAC blind index for login lookup.
 * Payloads: AES-GCM under a per-user DEK; the DEK is wrapped by the master key.
 * Keys are SHA-256-derived from configured secret strings; swapping to a real
 * KMS later touches only this class (spec: "KMS swap later touches only CryptoService").
 */
@Service
public class CryptoService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LEN = 12;      // GCM standard nonce
    private static final int TAG_BITS = 128;

    private final byte[] masterKey;
    private final byte[] blindIdxKey;

    public CryptoService(@Value("${rxscan.crypto.master-key}") String masterSecret,
                         @Value("${rxscan.crypto.blind-idx-key}") String blindIdxSecret) {
        this.masterKey = sha256(masterSecret);
        this.blindIdxKey = sha256(blindIdxSecret);
    }

    /** Keyed HMAC-SHA256 of the normalized phone — the login lookup key (no plaintext phone column). */
    public byte[] blindIndex(String phone) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(blindIdxKey, "HmacSHA256"));
            return mac.doFinal(phone.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("blind index failed", e);
        }
    }

    public byte[] newDek() {
        byte[] dek = new byte[32];
        RANDOM.nextBytes(dek);
        return dek;
    }

    public byte[] wrapDek(byte[] dek)        { return encrypt(dek, masterKey); }
    public byte[] unwrapDek(byte[] wrapped)  { return decrypt(wrapped, masterKey); }
    public byte[] encryptWithMaster(byte[] plain) { return encrypt(plain, masterKey); }
    public byte[] decryptWithMaster(byte[] blob)  { return decrypt(blob, masterKey); }

    /** AES-256-GCM; output = 12-byte IV || ciphertext+tag. */
    public byte[] encrypt(byte[] plain, byte[] key) {
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public byte[] decrypt(byte[] blob, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(blob, 0, IV_LEN)));
            return cipher.doFinal(Arrays.copyOfRange(blob, IV_LEN, blob.length));
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed (wrong key or tampered data)", e);
        }
    }

    private static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -q test -Dtest=CryptoServiceTest`
Expected: `Tests run: 5, Failures: 0`

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/rxscan/backend/auth/CryptoService.java \
        backend/src/test/java/com/rxscan/backend/auth/CryptoServiceTest.java \
        backend/src/main/resources/application.properties
git commit -m "backend: CryptoService — AES-GCM envelope crypto + HMAC blind index"
```

---

### Task 3: JwtService

**Files:**
- Modify: `backend/pom.xml` (jjwt dependencies)
- Create: `backend/src/main/java/com/rxscan/backend/auth/JwtService.java`
- Create: `backend/src/main/java/com/rxscan/backend/config/ClockConfig.java`
- Test: `backend/src/test/java/com/rxscan/backend/auth/JwtServiceTest.java`

**Interfaces:**
- Consumes: properties `rxscan.auth.jwt-secret`, `rxscan.auth.token-ttl-days` (Task 2).
- Produces: `String mint(String publicId)` · `java.util.Optional<String> verify(String token)` (empty on expired/tampered/garbage; present = the `sub`).

- [ ] **Step 1: Add jjwt to `pom.xml`** (in `<dependencies>`, after `commons-csv`)

```xml
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-api</artifactId>
			<version>0.13.0</version>
		</dependency>
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-impl</artifactId>
			<version>0.13.0</version>
			<scope>runtime</scope>
		</dependency>
		<dependency>
			<groupId>io.jsonwebtoken</groupId>
			<artifactId>jjwt-jackson</artifactId>
			<version>0.13.0</version>
			<scope>runtime</scope>
		</dependency>
```

- [ ] **Step 2: Write the failing test**

```java
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
```

- [ ] **Step 3: Run to verify it fails**

Run: `mvn -q test -Dtest=JwtServiceTest`
Expected: COMPILATION ERROR — `JwtService` does not exist.

- [ ] **Step 4: Implement**

`JwtService.java`:

```java
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
```

`ClockConfig.java` (so `JwtService` is injectable and tests can pin time):

```java
package com.rxscan.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -q test -Dtest=JwtServiceTest`
Expected: `Tests run: 3, Failures: 0`

- [ ] **Step 6: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/rxscan/backend/auth/JwtService.java \
        backend/src/main/java/com/rxscan/backend/config/ClockConfig.java \
        backend/src/test/java/com/rxscan/backend/auth/JwtServiceTest.java
git commit -m "backend: JwtService — jjwt HS256, 30-day tokens, sub = public_id"
```

---

### Task 4: Error shape + JwtInterceptor

**Files:**
- Create: `backend/src/main/java/com/rxscan/backend/auth/ApiException.java`
- Create: `backend/src/main/java/com/rxscan/backend/auth/ApiExceptionHandler.java`
- Create: `backend/src/main/java/com/rxscan/backend/auth/OtpDeliveryException.java`
- Create: `backend/src/main/java/com/rxscan/backend/auth/JwtInterceptor.java`
- Create: `backend/src/main/java/com/rxscan/backend/auth/WebConfig.java`
- Test: `backend/src/test/java/com/rxscan/backend/auth/JwtInterceptorTest.java`

**Interfaces:**
- Consumes: `JwtService.verify` (Task 3).
- Produces: `ApiException(int status, String code, String message)` thrown anywhere → `{"error":{"code","message"}}`; `JwtInterceptor.ATTR_PUBLIC_ID` request attribute (String UUID) on `/v1/me/**` and `/v1/prescriptions/**`; `OtpDeliveryException(String, Throwable)` → 503 `otp_delivery_failed`.

- [ ] **Step 1: Write the failing test**

```java
package com.rxscan.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

class JwtInterceptorTest {

    JwtService jwt = new JwtService("test-jwt-secret", 30, Clock.systemUTC());
    JwtInterceptor interceptor = new JwtInterceptor(jwt);

    @Test
    void validBearerTokenPassesAndSetsPublicId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + jwt.mint("some-public-id"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req, res, new Object())).isTrue();
        assertThat(req.getAttribute(JwtInterceptor.ATTR_PUBLIC_ID)).isEqualTo("some-public-id");
    }

    @Test
    void missingOrBadTokenIs401WithErrorShape() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req, res, new Object())).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("\"unauthorized\"");

        MockHttpServletRequest bad = new MockHttpServletRequest();
        bad.addHeader("Authorization", "Bearer garbage");
        assertThat(interceptor.preHandle(bad, new MockHttpServletResponse(), new Object())).isFalse();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=JwtInterceptorTest`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement all five classes**

`ApiException.java`:

```java
package com.rxscan.backend.auth;

/** Uniform client-facing error: HTTP status + machine code + human message (spec: error contract). */
public class ApiException extends RuntimeException {
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
```

`OtpDeliveryException.java`:

```java
package com.rxscan.backend.auth;

/** SMS vendor failure → 503 to the client (retryable). */
public class OtpDeliveryException extends RuntimeException {
    public OtpDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

`ApiExceptionHandler.java`:

```java
package com.rxscan.backend.auth;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status)
                .body(Map.of("error", Map.of("code", e.code, "message", e.getMessage())));
    }

    @ExceptionHandler(OtpDeliveryException.class)
    ResponseEntity<Map<String, Object>> otpDelivery(OtpDeliveryException e) {
        return ResponseEntity.status(503)
                .body(Map.of("error", Map.of("code", "otp_delivery_failed", "message", "Could not send OTP — try again")));
    }
}
```

`JwtInterceptor.java`:

```java
package com.rxscan.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Optional;

/**
 * Plain interceptor auth — no spring-security. Contract rule: any 401 makes the
 * FE clear its token and route to signin (no refresh tokens in slice A).
 */
public class JwtInterceptor implements HandlerInterceptor {

    public static final String ATTR_PUBLIC_ID = "rxscan.publicId";

    private final JwtService jwt;

    public JwtInterceptor(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Optional<String> sub = jwt.verify(header.substring(7));
            if (sub.isPresent()) {
                req.setAttribute(ATTR_PUBLIC_ID, sub.get());
                return true;
            }
        }
        res.setStatus(401);
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":{\"code\":\"unauthorized\",\"message\":\"Missing or invalid token\"}}");
        return false;
    }
}
```

`WebConfig.java`:

```java
package com.rxscan.backend.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** JWT gate on the consumer-plane routes. /v1/auth/** and /extract stay open. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final JwtService jwt;

    public WebConfig(JwtService jwt) {
        this.jwt = jwt;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new JwtInterceptor(jwt))
                .addPathPatterns("/v1/me/**", "/v1/prescriptions/**");
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest=JwtInterceptorTest`
Expected: `Tests run: 2, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/rxscan/backend/auth/ApiException.java \
        backend/src/main/java/com/rxscan/backend/auth/ApiExceptionHandler.java \
        backend/src/main/java/com/rxscan/backend/auth/OtpDeliveryException.java \
        backend/src/main/java/com/rxscan/backend/auth/JwtInterceptor.java \
        backend/src/main/java/com/rxscan/backend/auth/WebConfig.java \
        backend/src/test/java/com/rxscan/backend/auth/JwtInterceptorTest.java
git commit -m "backend: uniform error shape + JWT interceptor on consumer routes"
```

---

### Task 5: OTP provider strategy (stub + Gupshup)

**Files:**
- Create: `backend/src/main/java/com/rxscan/backend/auth/OtpSender.java`
- Create: `backend/src/main/java/com/rxscan/backend/auth/StubOtpSender.java`
- Create: `backend/src/main/java/com/rxscan/backend/auth/GupshupOtpSender.java`
- Create: `backend/src/main/java/com/rxscan/backend/auth/OtpConfig.java`
- Create: `backend/src/main/java/com/rxscan/backend/auth/OtpService.java`
- Test: `backend/src/test/java/com/rxscan/backend/auth/OtpServiceTest.java`
- Test: `backend/src/test/java/com/rxscan/backend/auth/GupshupOtpSenderTest.java`

**Interfaces:**
- Produces: `OtpService.request(String phone)` (throws `OtpDeliveryException` on vendor failure) · `boolean OtpService.verify(String phone, String otp)` · `interface OtpSender { void send(String phone, String otp); }`

- [ ] **Step 1: Write the failing tests**

`OtpServiceTest.java`:

```java
package com.rxscan.backend.auth;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtpServiceTest {

    @Test
    void stubModeAcceptsOnlyDevOtpAndSendsNothing() {
        List<String> sent = new ArrayList<>();
        OtpService otp = new OtpService((phone, code) -> sent.add(code), "stub", "000000");
        otp.request("+919876543210");
        assertThat(sent).isEmpty();
        assertThat(otp.verify("+919876543210", "000000")).isTrue();
        assertThat(otp.verify("+919876543210", "123456")).isFalse();
    }

    @Test
    void realModeGeneratesStoresAndVerifiesOnce() {
        List<String> sent = new ArrayList<>();
        OtpService otp = new OtpService((phone, code) -> sent.add(code), "gupshup", "000000");
        otp.request("+919876543210");
        assertThat(sent).hasSize(1);
        String code = sent.get(0);
        assertThat(code).matches("\\d{6}");
        assertThat(otp.verify("+919876543210", "000000")).isFalse(); // dev otp NOT accepted
        assertThat(otp.verify("+919876543210", code)).isTrue();
        assertThat(otp.verify("+919876543210", code)).isFalse();     // single use
    }
}
```

`GupshupOtpSenderTest.java`:

```java
package com.rxscan.backend.auth;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Request-shape test ONLY — never calls Gupshup for real (CLAUDE.md: no live SMS). */
class GupshupOtpSenderTest {

    @Test
    void sendsDltCompliantFormPost() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GupshupOtpSender sender = new GupshupOtpSender(
                builder.baseUrl("https://enterprise.smsgupshup.com").build(),
                "uid", "pw", "entity-1", "template-1", "Your RxScan OTP is %s");

        LinkedMultiValueMap<String, String> expected = new LinkedMultiValueMap<>();
        expected.add("method", "SendMessage");
        expected.add("send_to", "+919876543210");
        expected.add("msg", "Your RxScan OTP is 123456");
        expected.add("msg_type", "TEXT");
        expected.add("userid", "uid");
        expected.add("password", "pw");
        expected.add("v", "1.1");
        expected.add("auth_scheme", "plain");
        expected.add("format", "json");
        expected.add("principalEntityId", "entity-1");
        expected.add("dltTemplateId", "template-1");

        server.expect(requestTo("https://enterprise.smsgupshup.com/GatewayAPI/rest"))
                .andExpect(method(POST))
                .andExpect(content().formData(expected))
                .andRespond(withSuccess("{\"response\":{\"status\":\"success\"}}", APPLICATION_JSON));

        sender.send("+919876543210", "123456");
        server.verify();
    }

    @Test
    void gupshupErrorStatusThrows() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GupshupOtpSender sender = new GupshupOtpSender(
                builder.baseUrl("https://enterprise.smsgupshup.com").build(),
                "uid", "pw", "e", "t", "OTP %s");
        server.expect(requestTo("https://enterprise.smsgupshup.com/GatewayAPI/rest"))
                .andRespond(withSuccess("{\"response\":{\"status\":\"error\",\"details\":\"bad creds\"}}", APPLICATION_JSON));

        assertThatThrownBy(() -> sender.send("+919876543210", "123456"))
                .isInstanceOf(OtpDeliveryException.class);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `mvn -q test -Dtest='OtpServiceTest,GupshupOtpSenderTest'`
Expected: COMPILATION ERROR.

- [ ] **Step 3: Implement**

`OtpSender.java`:

```java
package com.rxscan.backend.auth;

/** OTP delivery strategy (mirrors the vision-provider pattern). */
public interface OtpSender {
    void send(String phone, String otp);
}
```

`StubOtpSender.java`:

```java
package com.rxscan.backend.auth;

/** Sends nothing; the accepted code is rxscan.auth.dev-otp. Default until the Gupshup contract closes. */
public class StubOtpSender implements OtpSender {
    @Override
    public void send(String phone, String otp) {
        // intentionally nothing
    }
}
```

`GupshupOtpSender.java`:

```java
package com.rxscan.backend.auth;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Gupshup Enterprise SMS (SMS only — no WhatsApp OTP, product decision). Dormant
 * until the contract + DLT sender-ID/template registration close (see CHECKLIST).
 * DLT params (principalEntityId, dltTemplateId) are mandatory for Indian A2P SMS.
 */
public class GupshupOtpSender implements OtpSender {

    private final RestClient client;
    private final String userId;
    private final String password;
    private final String principalEntityId;
    private final String templateId;
    private final String template;   // e.g. "Your RxScan OTP is %s" — must match the DLT-registered text

    public GupshupOtpSender(RestClient client, String userId, String password,
                            String principalEntityId, String templateId, String template) {
        this.client = client;
        this.userId = userId;
        this.password = password;
        this.principalEntityId = principalEntityId;
        this.templateId = templateId;
        this.template = template;
    }

    @Override
    public void send(String phone, String otp) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("method", "SendMessage");
        form.add("send_to", phone);
        form.add("msg", template.formatted(otp));
        form.add("msg_type", "TEXT");
        form.add("userid", userId);
        form.add("password", password);
        form.add("v", "1.1");
        form.add("auth_scheme", "plain");
        form.add("format", "json");
        form.add("principalEntityId", principalEntityId);
        form.add("dltTemplateId", templateId);
        try {
            String body = client.post().uri("/GatewayAPI/rest")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            // ponytail: string sniff instead of a response DTO — Gupshup's envelope is
            // {"response":{"status":"success"|"error",...}}; upgrade to a typed parse if it grows.
            if (body == null || body.contains("\"status\":\"error\"")) {
                throw new OtpDeliveryException("Gupshup rejected the send: " + body, null);
            }
        } catch (OtpDeliveryException e) {
            throw e;
        } catch (Exception e) {
            throw new OtpDeliveryException("Gupshup call failed", e);
        }
    }
}
```

`OtpConfig.java`:

```java
package com.rxscan.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class OtpConfig {

    @Bean
    OtpSender otpSender(@Value("${rxscan.otp.provider}") String provider,
                        @Value("${rxscan.otp.gupshup.user-id:}") String userId,
                        @Value("${rxscan.otp.gupshup.password:}") String password,
                        @Value("${rxscan.otp.gupshup.principal-entity-id:}") String entityId,
                        @Value("${rxscan.otp.gupshup.template-id:}") String templateId,
                        @Value("${rxscan.otp.gupshup.template:Your RxScan OTP is %s}") String template) {
        return switch (provider) {
            case "stub" -> new StubOtpSender();
            case "gupshup" -> new GupshupOtpSender(
                    RestClient.builder().baseUrl("https://enterprise.smsgupshup.com").build(),
                    userId, password, entityId, templateId, template);
            default -> throw new IllegalStateException("Unknown rxscan.otp.provider: " + provider);
        };
    }
}
```

`OtpService.java`:

```java
package com.rxscan.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues + verifies OTPs. In stub mode the accepted code is the configured dev OTP
 * and nothing is sent. In real mode a random 6-digit code is issued, sent via the
 * OtpSender, and accepted once within 5 minutes.
 * ponytail: in-memory OTP store — single node only; move to the consumer DB or a
 * cache if the backend ever runs more than one instance.
 */
@Service
public class OtpService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private record Issued(String code, Instant expiresAt) {}

    private final OtpSender sender;
    private final boolean stub;
    private final String devOtp;
    private final Map<String, Issued> issued = new ConcurrentHashMap<>();

    public OtpService(OtpSender sender,
                      @Value("${rxscan.otp.provider}") String provider,
                      @Value("${rxscan.auth.dev-otp}") String devOtp) {
        this.sender = sender;
        this.stub = "stub".equals(provider);
        this.devOtp = devOtp;
    }

    public void request(String phone) {
        if (stub) return;
        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        issued.put(phone, new Issued(code, Instant.now().plus(5, ChronoUnit.MINUTES)));
        sender.send(phone, code);
    }

    public boolean verify(String phone, String otp) {
        if (stub) return devOtp.equals(otp);
        Issued i = issued.remove(phone);
        return i != null && i.expiresAt().isAfter(Instant.now()) && i.code().equals(otp);
    }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `mvn -q test -Dtest='OtpServiceTest,GupshupOtpSenderTest'`
Expected: `Tests run: 4, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/rxscan/backend/auth/OtpSender.java \
        backend/src/main/java/com/rxscan/backend/auth/StubOtpSender.java \
        backend/src/main/java/com/rxscan/backend/auth/GupshupOtpSender.java \
        backend/src/main/java/com/rxscan/backend/auth/OtpConfig.java \
        backend/src/main/java/com/rxscan/backend/auth/OtpService.java \
        backend/src/test/java/com/rxscan/backend/auth/OtpServiceTest.java \
        backend/src/test/java/com/rxscan/backend/auth/GupshupOtpSenderTest.java
git commit -m "backend: OTP provider strategy — stub default, Gupshup DLT-ready"
```

---

### Task 6: UserRepository + ConsentRepository + AuthController (first end-to-end endpoint)

**Files:**
- Modify: `backend/src/main/java/com/rxscan/backend/config/DataSourceConfig.java` (add `consumerJdbcClient` bean)
- Create: `backend/src/main/java/com/rxscan/backend/auth/UserRepository.java`
- Create: `backend/src/main/java/com/rxscan/backend/auth/CurrentUser.java`
- Create: `backend/src/main/java/com/rxscan/backend/consent/ConsentDto.java`
- Create: `backend/src/main/java/com/rxscan/backend/consent/ConsentRepository.java`
- Create: `backend/src/main/java/com/rxscan/backend/auth/AuthController.java`
- Create: `backend/src/test/java/com/rxscan/backend/ConsumerApiTestBase.java`
- Test: `backend/src/test/java/com/rxscan/backend/auth/AuthControllerIT.java`

**Interfaces:**
- Produces: `UserRepository.UserRow(long userId, java.util.UUID publicId, byte[] dekWrapped)`; `Optional<UserRow> findByBlindIndex(byte[])` · `UserRow create(byte[] phoneEnc, byte[] blindIdx, byte[] dekWrapped)` · `Optional<UserRow> findByPublicId(UUID)`.
- `CurrentUser.require(HttpServletRequest req)` → `UserRow` (throws 401 `ApiException` if the JWT sub maps to no user).
- `ConsentDto(String purpose, boolean granted, OffsetDateTime grantedAt)` (JSON: `granted_at`).
- `ConsentRepository.insert(long userId, String purpose, boolean granted, OffsetDateTime grantedAt)`.
- Endpoints: `POST /v1/auth/otp/request` `{phone}` → `200 {}`; `POST /v1/auth/otp/verify` `{phone, otp, consents:[...]}` → `200 {token, user_created}`.
- `ConsumerApiTestBase`: `@SpringBootTest @AutoConfigureMockMvc` with a shared postgres:16 Testcontainer (both DBs), `protected MockMvc mvc`, `protected JdbcTemplate consumerJdbc`, helper `protected String signIn(String phone)` returning a JWT via the stub OTP.

- [ ] **Step 1: Add the JdbcClient bean to `DataSourceConfig`** (below the `consumerJdbc` bean)

```java
    @Bean
    JdbcClient consumerJdbcClient(@Qualifier("consumerDataSource") DataSource ds) {
        return JdbcClient.create(ds);
    }
```

Add import: `import org.springframework.jdbc.core.simple.JdbcClient;`

- [ ] **Step 2: Write the test base + failing integration test**

`ConsumerApiTestBase.java`:

```java
package com.rxscan.backend;

import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared base for consumer-plane endpoint tests: one postgres:16 container for the
 * whole test JVM (same static-block pattern as BackendApplicationTests), MockMvc,
 * and a stub-OTP sign-in helper. No live SMS, no live AI — ever.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ConsumerApiTestBase {

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
    protected MockMvc mvc;

    @Autowired
    @Qualifier("consumerJdbc")
    protected JdbcTemplate consumerJdbc;

    /** Stub-OTP sign-in (000000); returns the bearer token. */
    protected String signIn(String phone) throws Exception {
        String body = mvc.perform(post("/v1/auth/otp/verify").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"otp\":\"000000\",\"consents\":[]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
```

Note: if `com.jayway.jsonpath` isn't on the test classpath (it ships with the webmvc test starter), add `json-path` test-scope to `pom.xml`.

`AuthControllerIT.java`:

```java
package com.rxscan.backend.auth;

import com.rxscan.backend.ConsumerApiTestBase;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerIT extends ConsumerApiTestBase {

    @Test
    void otpRequestValidatesPhone() throws Exception {
        mvc.perform(post("/v1/auth/otp/request").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"9876543210\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/auth/otp/request").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"12345\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("invalid_phone"));
    }

    @Test
    void wrongOtpIs401() throws Exception {
        mvc.perform(post("/v1/auth/otp/verify").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"9876543210\",\"otp\":\"999999\",\"consents\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("invalid_otp"));
    }

    @Test
    void verifyCreatesUserOnceStoresConsentsAndMintsOpaqueSub() throws Exception {
        String body = mvc.perform(post("/v1/auth/otp/verify").contentType(APPLICATION_JSON)
                        .content("""
                                {"phone":"9876500001","otp":"000000","consents":[
                                  {"purpose":"process","granted":true,"granted_at":"2026-07-23T10:00:00+05:30"},
                                  {"purpose":"retain_optin","granted":false,"granted_at":"2026-07-23T10:00:01+05:30"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_created").value(true))
                .andReturn().getResponse().getContentAsString();

        // Same phone again: existing user, consents appended not replaced.
        mvc.perform(post("/v1/auth/otp/verify").contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"9876500001\",\"otp\":\"000000\",\"consents\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user_created").value(false));

        Integer users = consumerJdbc.queryForObject(
                "SELECT count(*) FROM users WHERE phone_blind_idx IS NOT NULL", Integer.class);
        Integer consents = consumerJdbc.queryForObject(
                "SELECT count(*) FROM user_consent", Integer.class);
        assertThat(users).isEqualTo(1);
        assertThat(consents).isEqualTo(2);

        // No plaintext phone at rest.
        byte[] phoneEnc = consumerJdbc.queryForObject("SELECT phone_enc FROM users LIMIT 1", byte[].class);
        assertThat(new String(phoneEnc, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("9876500001");

        // CLAUDE.md rule: JWT sub is the opaque public_id (UUID), never the sequential user_id.
        String token = com.jayway.jsonpath.JsonPath.read(body, "$.token");
        String claims = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(claims).matches(".*\"sub\":\"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\".*");
    }
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `mvn -q test -Dtest=AuthControllerIT`
Expected: COMPILATION ERROR (`UserRepository`, `AuthController` missing).

- [ ] **Step 4: Implement**

`UserRepository.java`:

```java
package com.rxscan.backend.auth;

import org.springframework.beans.factory.annotation.Qualifier;
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

    public UserRepository(@Qualifier("consumerJdbcClient") JdbcClient jdbc) {
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

    public UserRow create(byte[] phoneEnc, byte[] blindIdx, byte[] dekWrapped) {
        return jdbc.sql("""
                        INSERT INTO users (phone_enc, phone_blind_idx, dek_wrapped)
                        VALUES (:phone, :idx, :dek)
                        RETURNING user_id, public_id, dek_wrapped""")
                .param("phone", phoneEnc).param("idx", blindIdx).param("dek", dekWrapped)
                .query(ROW).single();
    }
}
```

`CurrentUser.java`:

```java
package com.rxscan.backend.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Resolves the JWT sub (public_id) set by JwtInterceptor to the internal user row. */
@Component
public class CurrentUser {

    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public UserRepository.UserRow require(HttpServletRequest req) {
        Object publicId = req.getAttribute(JwtInterceptor.ATTR_PUBLIC_ID);
        if (publicId == null) throw new ApiException(401, "unauthorized", "Missing token");
        return users.findByPublicId(UUID.fromString((String) publicId))
                .orElseThrow(() -> new ApiException(401, "unauthorized", "Unknown user"));
    }
}
```

`consent/ConsentDto.java`:

```java
package com.rxscan.backend.consent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/** granted_at is the DEVICE-side grant time; the server receipt is the row's created_at. */
public record ConsentDto(String purpose, boolean granted,
                         @JsonProperty("granted_at") OffsetDateTime grantedAt) {}
```

`consent/ConsentRepository.java`:

```java
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
```

`AuthController.java`:

```java
package com.rxscan.backend.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rxscan.backend.consent.ConsentDto;
import com.rxscan.backend.consent.ConsentRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/auth/otp")
public class AuthController {

    record OtpRequestBody(String phone) {}
    record OtpVerifyBody(String phone, String otp, List<ConsentDto> consents) {}
    record TokenResponse(String token, @JsonProperty("user_created") boolean userCreated) {}

    private final OtpService otp;
    private final CryptoService crypto;
    private final JwtService jwt;
    private final UserRepository users;
    private final ConsentRepository consents;

    public AuthController(OtpService otp, CryptoService crypto, JwtService jwt,
                          UserRepository users, ConsentRepository consents) {
        this.otp = otp;
        this.crypto = crypto;
        this.jwt = jwt;
        this.users = users;
        this.consents = consents;
    }

    @PostMapping("/request")
    Map<String, Object> request(@RequestBody OtpRequestBody body) {
        otp.request(normalize(body.phone()));
        return Map.of();
    }

    @PostMapping("/verify")
    TokenResponse verify(@RequestBody OtpVerifyBody body) {
        String phone = normalize(body.phone());
        if (!otp.verify(phone, body.otp())) {
            throw new ApiException(401, "invalid_otp", "OTP did not match");
        }
        byte[] blindIdx = crypto.blindIndex(phone);
        boolean created = false;
        UserRepository.UserRow user = users.findByBlindIndex(blindIdx).orElse(null);
        if (user == null) {
            user = users.create(
                    crypto.encryptWithMaster(phone.getBytes(StandardCharsets.UTF_8)),
                    blindIdx,
                    crypto.wrapDek(crypto.newDek()));
            created = true;
        }
        for (ConsentDto c : body.consents() == null ? List.<ConsentDto>of() : body.consents()) {
            consents.insert(user.userId(), c.purpose(), c.granted(), c.grantedAt());
        }
        return new TokenResponse(jwt.mint(user.publicId().toString()), created);
    }

    /** Accepts 10-digit Indian mobiles (optionally +91-prefixed); normalizes to +91XXXXXXXXXX. */
    static String normalize(String phone) {
        String p = phone == null ? "" : phone.replaceAll("[\\s-]", "");
        if (p.matches("[6-9]\\d{9}")) return "+91" + p;
        if (p.matches("\\+91[6-9]\\d{9}")) return p;
        throw new ApiException(422, "invalid_phone", "Use a 10-digit Indian mobile number");
    }
}
```

- [ ] **Step 5: Run to verify it passes**

Run: `mvn -q test -Dtest=AuthControllerIT`
Expected: `Tests run: 3, Failures: 0` (needs Docker running).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/rxscan/backend/config/DataSourceConfig.java \
        backend/src/main/java/com/rxscan/backend/auth/UserRepository.java \
        backend/src/main/java/com/rxscan/backend/auth/CurrentUser.java \
        backend/src/main/java/com/rxscan/backend/consent/ConsentDto.java \
        backend/src/main/java/com/rxscan/backend/consent/ConsentRepository.java \
        backend/src/main/java/com/rxscan/backend/auth/AuthController.java \
        backend/src/test/java/com/rxscan/backend/ConsumerApiTestBase.java \
        backend/src/test/java/com/rxscan/backend/auth/AuthControllerIT.java
git commit -m "backend: phone-OTP auth — blind-index login, consents at verify, JWT"
```

---

### Task 7: ConsentController (`PUT /v1/me/consents`)

**Files:**
- Create: `backend/src/main/java/com/rxscan/backend/consent/ConsentController.java`
- Test: `backend/src/test/java/com/rxscan/backend/consent/ConsentControllerIT.java`

**Interfaces:**
- Consumes: `CurrentUser.require`, `ConsentRepository.insert`, `ConsentDto` (Task 6).
- Produces: `PUT /v1/me/consents` Bearer `{consents:[...]}` → `204`.

- [ ] **Step 1: Write the failing test**

```java
package com.rxscan.backend.consent;

import com.rxscan.backend.ConsumerApiTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ConsentControllerIT extends ConsumerApiTestBase {

    @Test
    void appendsConsentRowsForTheAuthedUser() throws Exception {
        String token = signIn("9876500002");
        Integer before = consumerJdbc.queryForObject("SELECT count(*) FROM user_consent", Integer.class);

        mvc.perform(put("/v1/me/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"consents\":[{\"purpose\":\"notify\",\"granted\":true,\"granted_at\":\"2026-07-23T11:00:00+05:30\"}]}"))
                .andExpect(status().isNoContent());

        Integer after = consumerJdbc.queryForObject("SELECT count(*) FROM user_consent", Integer.class);
        assertThat(after - before).isEqualTo(1);
    }

    @Test
    void requiresAuth() throws Exception {
        mvc.perform(put("/v1/me/consents").contentType(APPLICATION_JSON)
                        .content("{\"consents\":[]}"))
                .andExpect(status().isUnauthorized());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=ConsentControllerIT`
Expected: FAIL — 404s (no controller yet; note the 401 test may already pass via the interceptor — the append test is the true failing gate).

- [ ] **Step 3: Implement**

```java
package com.rxscan.backend.consent;

import com.rxscan.backend.auth.CurrentUser;
import com.rxscan.backend.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Post-login consent upload — e.g. `notify` from the notif-perm screen (login is late in the flow). */
@RestController
@RequestMapping("/v1/me/consents")
public class ConsentController {

    record Body(List<ConsentDto> consents) {}

    private final CurrentUser currentUser;
    private final ConsentRepository consents;

    public ConsentController(CurrentUser currentUser, ConsentRepository consents) {
        this.currentUser = currentUser;
        this.consents = consents;
    }

    @PutMapping
    ResponseEntity<Void> put(HttpServletRequest req, @RequestBody Body body) {
        UserRepository.UserRow user = currentUser.require(req);
        for (ConsentDto c : body.consents() == null ? List.<ConsentDto>of() : body.consents()) {
            consents.insert(user.userId(), c.purpose(), c.granted(), c.grantedAt());
        }
        return ResponseEntity.noContent().build();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest=ConsentControllerIT`
Expected: `Tests run: 2, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/rxscan/backend/consent/ConsentController.java \
        backend/src/test/java/com/rxscan/backend/consent/ConsentControllerIT.java
git commit -m "backend: PUT /v1/me/consents — post-login consent append"
```

---

### Task 8: PayloadCodec + preferences endpoints

**Files:**
- Create: `backend/src/main/java/com/rxscan/backend/auth/PayloadCodec.java`
- Create: `backend/src/main/java/com/rxscan/backend/preference/PreferenceRepository.java`
- Create: `backend/src/main/java/com/rxscan/backend/preference/PreferenceController.java`
- Test: `backend/src/test/java/com/rxscan/backend/preference/PreferenceControllerIT.java`

**Interfaces:**
- Produces: `PayloadCodec.encrypt(JsonNode payload, byte[] dekWrapped)` → `byte[]` (throws 422 `invalid_payload` on null, 413 `payload_too_large` over 256 KB) · `PayloadCodec.decrypt(byte[] blob, byte[] dekWrapped)` → `JsonNode`.
- `PreferenceRepository.upsert(long userId, byte[] payloadEnc)` · `record PreferenceRow(byte[] payloadEnc, OffsetDateTime updatedAt)` · `Optional<PreferenceRow> find(long userId)`.
- Endpoints: `PUT /v1/me/preferences` `{payload}` → `204`; `GET /v1/me/preferences` → `200 {payload, updated_at}` | `404 not_found`.

- [ ] **Step 1: Write the failing test**

```java
package com.rxscan.backend.preference;

import com.rxscan.backend.ConsumerApiTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PreferenceControllerIT extends ConsumerApiTestBase {

    @Test
    void roundTripUpsertAndEncryptionAtRest() throws Exception {
        String token = signIn("9876500003");

        mvc.perform(get("/v1/me/preferences").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        String prefs = "{\"payload\":{\"schema\":1,\"mealTimes\":{\"breakfast\":\"08:00\",\"lunch\":\"13:00\",\"dinner\":\"20:30\"}}}";
        mvc.perform(put("/v1/me/preferences").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(prefs))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/me/preferences").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.mealTimes.breakfast").value("08:00"))
                .andExpect(jsonPath("$.updated_at").exists());

        // Upsert: second PUT replaces, still exactly one row for this user.
        mvc.perform(put("/v1/me/preferences").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"payload\":{\"schema\":1,\"mealTimes\":{\"breakfast\":\"07:30\"}}}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/v1/me/preferences").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.payload.mealTimes.breakfast").value("07:30"));

        // Server-opaque: nothing readable at rest.
        byte[] enc = consumerJdbc.queryForObject(
                "SELECT payload_enc FROM user_preference LIMIT 1", byte[].class);
        assertThat(new String(enc, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("breakfast");
    }

    @Test
    void oversizePayloadIs413() throws Exception {
        String token = signIn("9876500004");
        String big = "{\"payload\":{\"blob\":\"" + "x".repeat(300 * 1024) + "\"}}";
        mvc.perform(put("/v1/me/preferences").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("payload_too_large"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=PreferenceControllerIT`
Expected: FAIL — 404s.

- [ ] **Step 3: Implement**

`PayloadCodec.java`:

```java
package com.rxscan.backend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Opaque-payload envelope: the server stores FE-owned JSON encrypted under the
 * user's DEK and NEVER parses it (spec invariant). 256 KB cap.
 */
@Component
public class PayloadCodec {

    static final int MAX_BYTES = 256 * 1024;

    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public PayloadCodec(CryptoService crypto, ObjectMapper mapper) {
        this.crypto = crypto;
        this.mapper = mapper;
    }

    public byte[] encrypt(JsonNode payload, byte[] dekWrapped) {
        if (payload == null || payload.isNull()) {
            throw new ApiException(422, "invalid_payload", "payload is required");
        }
        byte[] plain;
        try {
            plain = mapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new ApiException(422, "invalid_payload", "payload is not valid JSON");
        }
        if (plain.length > MAX_BYTES) {
            throw new ApiException(413, "payload_too_large", "payload exceeds 256 KB");
        }
        return crypto.encrypt(plain, crypto.unwrapDek(dekWrapped));
    }

    public JsonNode decrypt(byte[] blob, byte[] dekWrapped) {
        try {
            return mapper.readTree(crypto.decrypt(blob, crypto.unwrapDek(dekWrapped)));
        } catch (Exception e) {
            throw new IllegalStateException("stored payload unreadable", e);
        }
    }
}
```

`PreferenceRepository.java`:

```java
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
```

`PreferenceController.java`:

```java
package com.rxscan.backend.preference;

import com.fasterxml.jackson.databind.JsonNode;
import com.rxscan.backend.auth.ApiException;
import com.rxscan.backend.auth.CurrentUser;
import com.rxscan.backend.auth.PayloadCodec;
import com.rxscan.backend.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** FE-owned preferences blob (meal times, toggles). Everything the FE store holds reaches the BE after login. */
@RestController
@RequestMapping("/v1/me/preferences")
public class PreferenceController {

    record PutBody(JsonNode payload) {}

    private final CurrentUser currentUser;
    private final PayloadCodec codec;
    private final PreferenceRepository preferences;

    public PreferenceController(CurrentUser currentUser, PayloadCodec codec, PreferenceRepository preferences) {
        this.currentUser = currentUser;
        this.codec = codec;
        this.preferences = preferences;
    }

    @PutMapping
    ResponseEntity<Void> put(HttpServletRequest req, @RequestBody PutBody body) {
        UserRepository.UserRow user = currentUser.require(req);
        preferences.upsert(user.userId(), codec.encrypt(body.payload(), user.dekWrapped()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    Map<String, Object> get(HttpServletRequest req) {
        UserRepository.UserRow user = currentUser.require(req);
        PreferenceRepository.PreferenceRow row = preferences.find(user.userId())
                .orElseThrow(() -> new ApiException(404, "not_found", "No preferences saved yet"));
        return Map.of("payload", codec.decrypt(row.payloadEnc(), user.dekWrapped()),
                      "updated_at", row.updatedAt());
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest=PreferenceControllerIT`
Expected: `Tests run: 2, Failures: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/rxscan/backend/auth/PayloadCodec.java \
        backend/src/main/java/com/rxscan/backend/preference/ \
        backend/src/test/java/com/rxscan/backend/preference/
git commit -m "backend: preferences endpoints — encrypted FE-owned blob, upsert + rehydrate"
```

---

### Task 9: Prescription endpoints

**Files:**
- Create: `backend/src/main/java/com/rxscan/backend/prescription/PrescriptionRepository.java`
- Create: `backend/src/main/java/com/rxscan/backend/prescription/PrescriptionController.java`
- Test: `backend/src/test/java/com/rxscan/backend/prescription/PrescriptionControllerIT.java`

**Interfaces:**
- Consumes: `CurrentUser`, `PayloadCodec` (Tasks 6/8).
- Produces: `PrescriptionRepository`: `record Created(UUID rxId, OffsetDateTime updatedAt)` · `Created insert(long userId, byte[] payloadEnc)` · `Optional<OffsetDateTime> update(UUID rxId, long userId, byte[] payloadEnc)` (empty = not found / not owner) · `record RxRow(UUID rxId, byte[] payloadEnc, OffsetDateTime createdAt, OffsetDateTime updatedAt)` · `List<RxRow> findSince(long userId, OffsetDateTime since)` (`since` nullable = full pull).
- Endpoints: `POST /v1/prescriptions` `{payload}` → `201 {rx_id, updated_at}`; `PATCH /v1/prescriptions/{rxId}` → `200 {updated_at}` | `404`; `GET /v1/prescriptions?since=` → `200 {prescriptions:[{rx_id, payload, created_at, updated_at}]}`.

- [ ] **Step 1: Write the failing test**

```java
package com.rxscan.backend.prescription;

import com.jayway.jsonpath.JsonPath;
import com.rxscan.backend.ConsumerApiTestBase;
import org.junit.jupiter.api.Test;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PrescriptionControllerIT extends ConsumerApiTestBase {

    static final String MEDS = "{\"payload\":{\"schema\":1,\"meds\":[{\"name\":\"Augmentin 625\",\"strength\":\"625mg\",\"slots\":[\"morning\",\"night\"],\"mealTiming\":\"after_food\",\"durationDays\":5,\"prn\":false}],\"confirmedAt\":\"2026-07-23T10:30:00+05:30\"}}";

    @Test
    void saveEditSyncRoundTrip() throws Exception {
        String token = signIn("9876500005");

        String created = mvc.perform(post("/v1/prescriptions").header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON).content(MEDS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rx_id").exists())
                .andExpect(jsonPath("$.updated_at").exists())
                .andReturn().getResponse().getContentAsString();
        String rxId = JsonPath.read(created, "$.rx_id");

        mvc.perform(patch("/v1/prescriptions/" + rxId).header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content("{\"payload\":{\"schema\":1,\"meds\":[],\"confirmedAt\":\"2026-07-23T11:00:00+05:30\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updated_at").exists());

        mvc.perform(get("/v1/prescriptions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prescriptions.length()").value(1))
                .andExpect(jsonPath("$.prescriptions[0].rx_id").value(rxId))
                .andExpect(jsonPath("$.prescriptions[0].payload.schema").value(1));

        // since= far in the future filters everything out.
        mvc.perform(get("/v1/prescriptions").param("since", "2099-01-01T00:00:00Z")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.prescriptions.length()").value(0));
    }

    @Test
    void cannotTouchAnotherUsersPrescription() throws Exception {
        String alice = signIn("9876500006");
        String bob = signIn("9876500007");
        String created = mvc.perform(post("/v1/prescriptions").header("Authorization", "Bearer " + alice)
                        .contentType(APPLICATION_JSON).content(MEDS))
                .andReturn().getResponse().getContentAsString();
        String rxId = JsonPath.read(created, "$.rx_id");

        mvc.perform(patch("/v1/prescriptions/" + rxId).header("Authorization", "Bearer " + bob)
                        .contentType(APPLICATION_JSON).content(MEDS))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
        mvc.perform(get("/v1/prescriptions").header("Authorization", "Bearer " + bob))
                .andExpect(jsonPath("$.prescriptions.length()").value(0));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn -q test -Dtest=PrescriptionControllerIT`
Expected: FAIL — 404s.

- [ ] **Step 3: Implement**

`PrescriptionRepository.java`:

```java
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
```

`PrescriptionController.java`:

```java
package com.rxscan.backend.prescription;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.rxscan.backend.auth.ApiException;
import com.rxscan.backend.auth.CurrentUser;
import com.rxscan.backend.auth.PayloadCodec;
import com.rxscan.backend.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The confirmed prescription record — deferred save: the FE holds it in Room
 * (pendingSync) until OTP verify succeeds, then POSTs. Payload is FE-owned and
 * server-opaque (encrypted under the user's DEK; the server never parses it).
 */
@RestController
@RequestMapping("/v1/prescriptions")
public class PrescriptionController {

    record PutBody(JsonNode payload) {}
    record CreatedResponse(@JsonProperty("rx_id") UUID rxId,
                           @JsonProperty("updated_at") OffsetDateTime updatedAt) {}
    record RxResponse(@JsonProperty("rx_id") UUID rxId, JsonNode payload,
                      @JsonProperty("created_at") OffsetDateTime createdAt,
                      @JsonProperty("updated_at") OffsetDateTime updatedAt) {}

    private final CurrentUser currentUser;
    private final PayloadCodec codec;
    private final PrescriptionRepository prescriptions;

    public PrescriptionController(CurrentUser currentUser, PayloadCodec codec,
                                  PrescriptionRepository prescriptions) {
        this.currentUser = currentUser;
        this.codec = codec;
        this.prescriptions = prescriptions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreatedResponse create(HttpServletRequest req, @RequestBody PutBody body) {
        UserRepository.UserRow user = currentUser.require(req);
        PrescriptionRepository.Created created =
                prescriptions.insert(user.userId(), codec.encrypt(body.payload(), user.dekWrapped()));
        return new CreatedResponse(created.rxId(), created.updatedAt());
    }

    @PatchMapping("/{rxId}")
    Map<String, Object> update(HttpServletRequest req, @PathVariable UUID rxId, @RequestBody PutBody body) {
        UserRepository.UserRow user = currentUser.require(req);
        OffsetDateTime updatedAt = prescriptions
                .update(rxId, user.userId(), codec.encrypt(body.payload(), user.dekWrapped()))
                .orElseThrow(() -> new ApiException(404, "not_found", "No such prescription"));
        return Map.of("updated_at", updatedAt);
    }

    @GetMapping
    Map<String, Object> list(HttpServletRequest req,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since) {
        UserRepository.UserRow user = currentUser.require(req);
        List<RxResponse> out = prescriptions.findSince(user.userId(), since).stream()
                .map(r -> new RxResponse(r.rxId(), codec.decrypt(r.payloadEnc(), user.dekWrapped()),
                        r.createdAt(), r.updatedAt()))
                .toList();
        return Map.of("prescriptions", out);
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn -q test -Dtest=PrescriptionControllerIT`
Expected: `Tests run: 2, Failures: 0`

- [ ] **Step 5: Run the FULL suite (regression gate)**

Run: `mvn -q test`
Expected: all green — including the pre-existing extraction/parser tests.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/rxscan/backend/prescription/ \
        backend/src/test/java/com/rxscan/backend/prescription/
git commit -m "backend: prescription save/edit/sync — encrypted, owner-scoped, since-pull"
```

---

### Task 10: Contract doc + smoke test + status close-out

**Files:**
- Create: `docs/api-contract-v1.md`
- Modify: `/Users/ankitjain/rxscan/CHECKLIST.md` (move OTP + persist-prescription to Done; point FE bullets at the contract doc)
- Modify: `/Users/ankitjain/rxscan/CLAUDE.md` (Current status + date — finalizes the pending edit from Task 1)

**Interfaces:**
- Consumes: every endpoint from Tasks 6–9, verified live.

- [ ] **Step 1: Manual smoke test against the real dev DBs**

```bash
cd /Users/ankitjain/rxscan/backend && mvn -q spring-boot:run &   # wait for boot
TOKEN=$(curl -s -X POST localhost:8080/v1/auth/otp/verify -H 'Content-Type: application/json' \
  -d '{"phone":"9876543210","otp":"000000","consents":[{"purpose":"process","granted":true,"granted_at":"2026-07-23T10:00:00+05:30"}]}' | jq -r .token)
curl -s -X PUT localhost:8080/v1/me/preferences -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"payload":{"schema":1,"mealTimes":{"breakfast":"08:00","lunch":"13:00","dinner":"20:30"}}}' -o /dev/null -w '%{http_code}\n'   # 204
curl -s localhost:8080/v1/me/preferences -H "Authorization: Bearer $TOKEN" | jq .payload.mealTimes
RX=$(curl -s -X POST localhost:8080/v1/prescriptions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"payload":{"schema":1,"meds":[{"name":"Augmentin 625","strength":"625mg","slots":["morning","night"],"mealTiming":"after_food","durationDays":5,"prn":false}],"confirmedAt":"2026-07-23T10:30:00+05:30"}}' | jq -r .rx_id)
curl -s "localhost:8080/v1/prescriptions" -H "Authorization: Bearer $TOKEN" | jq '.prescriptions[0].rx_id'
curl -s localhost:8080/v1/me/preferences | jq .   # NO token → {"error":{"code":"unauthorized",...}}
# stop the server when done
```

Expected: 204, meal times echoed, a UUID rx_id, and the unauthorized error shape.

- [ ] **Step 2: Write `docs/api-contract-v1.md`** — full content:

````markdown
# RxScan API contract v1

FE ↔ BE contract for slice A. Backend spec: `docs/superpowers/specs/2026-07-23-consumer-api-v1-design.md`.
Base URL (emulator): `http://10.0.2.2:8080/`

## Contract rules (product invariants)

- **No `suggested_value` exists in any schema** (CDSCO: flag, don't correct).
- **Phone is the only PII.** Sent once per login flow; stored encrypted + blind-indexed.
- **`payload` is opaque to the server** — FE-owned JSON, stored encrypted, round-tripped verbatim.
- **Any `401` ⇒ FE clears the token and routes to signin.** No refresh tokens in v1.
- All errors: `{"error":{"code":"...","message":"..."}}`. Codes: `invalid_phone` 422 ·
  `invalid_otp` 401 · `unauthorized` 401 · `not_found` 404 · `payload_too_large` 413 (cap 256 KB) ·
  `invalid_payload` 422 · `otp_delivery_failed` 503.

## Auth

### POST /v1/auth/otp/request
```bash
curl -X POST $BASE/v1/auth/otp/request -H 'Content-Type: application/json' \
  -d '{"phone":"9876543210"}'
```
`200 {}` — OTP sent (dev stub: nothing sent, the accepted code is `000000`). `422 invalid_phone`.

### POST /v1/auth/otp/verify
```bash
curl -X POST $BASE/v1/auth/otp/verify -H 'Content-Type: application/json' \
  -d '{"phone":"9876543210","otp":"000000","consents":[
        {"purpose":"process","granted":true,"granted_at":"2026-07-23T10:00:00+05:30"}]}'
```
`200 {"token":"<jwt>","user_created":true}` — 30-day Bearer JWT; send as
`Authorization: Bearer <jwt>` on every endpoint below. `consents` = everything the FE
held locally pre-login (purposes: `process` | `notify` | `retain_optin`; `granted_at` =
device-side grant time). `401 invalid_otp`.

## Me

### PUT /v1/me/consents  (Bearer)
```bash
curl -X PUT $BASE/v1/me/consents -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"consents":[{"purpose":"notify","granted":true,"granted_at":"2026-07-23T11:00:00+05:30"}]}'
```
`204` — rows are append-only; withdrawal = new row with `granted:false`.

### PUT /v1/me/preferences  (Bearer)
```bash
curl -X PUT $BASE/v1/me/preferences -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"payload":{"schema":1,"mealTimes":{"breakfast":"08:00","lunch":"13:00","dinner":"20:30"}}}'
```
`204` — upsert, exactly one blob per user. Push right after login and on every change.

### GET /v1/me/preferences  (Bearer)
`200 {"payload":{...},"updated_at":"..."}` · `404 not_found` if never set. Pull on device rehydrate.

**Preferences payload schema (FE-owned, additive-only):**
`{"schema":1,"mealTimes":{"breakfast":"HH:mm","lunch":"HH:mm","dinner":"HH:mm"}}`

## Prescriptions

### POST /v1/prescriptions  (Bearer)
```bash
curl -X POST $BASE/v1/prescriptions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"payload":{"schema":1,"meds":[{"name":"Augmentin 625","strength":"625mg",
       "slots":["morning","night"],"mealTiming":"after_food","durationDays":5,"prn":false}],
       "confirmedAt":"2026-07-23T10:30:00+05:30"}}'
```
`201 {"rx_id":"<uuid>","updated_at":"..."}` — deferred save: fire right after OTP verify succeeds.

### PATCH /v1/prescriptions/{rx_id}  (Bearer)
Same body. `200 {"updated_at":"..."}` · `404` (also for someone else's `rx_id`).

### GET /v1/prescriptions?since=<ISO8601>  (Bearer)
`200 {"prescriptions":[{"rx_id":"...","payload":{...},"created_at":"...","updated_at":"..."}]}`
`since` optional — absent ⇒ full pull (device rehydrate).

**Meds payload schema (FE-owned):**
`{"schema":1,"meds":[{"name","strength","slots":["morning"|"afternoon"|"night"],"mealTiming":"before_food"|"after_food"|null,"durationDays":int|null,"prn":bool}],"confirmedAt":ISO8601}`
There is no field for a system-suggested value, by design.

## Extraction (engine plane — unchanged, no user identity)

### POST /extract
Multipart image upload; no auth header today (client-key auth is a pending backend task).
Returns the flag-annotated extraction JSON the Verify screen renders. Errors:
`413` oversize (10 MB) · `415` bad type · `502` upstream vision failure · `503` vision unavailable.
The extraction result schema is documented in `docs/rxscan-tech-design-v0_2_3.md` §4.
````

- [ ] **Step 3: Update CHECKLIST.md**

Move to Done (with `[x]`): "OTP / sign-in endpoint" and "Persist prescription"; reword the Android "Sign-in / OTP wired to real backend" bullet to reference `docs/api-contract-v1.md`.

- [ ] **Step 4: Finalize CLAUDE.md**

Update "Current status" (+ date): consumer API v1 slice A backend DONE — auth/consents/preferences/prescriptions live, stub OTP `000000`, contract doc at `docs/api-contract-v1.md`, `mvn` not `./mvnw` on this machine; next = FE wiring plan.

- [ ] **Step 5: Final commit**

```bash
git add docs/api-contract-v1.md CHECKLIST.md CLAUDE.md
git commit -m "docs: api-contract-v1 — FE-facing contract for consumer slice A"
```

---

## Self-Review (done at plan-writing time)

- **Spec coverage:** endpoints 1–8 ✓ (Tasks 6–9), OTP strategy + Gupshup sample ✓ (5), auth mechanics (blind index, DEK, jjwt, interceptor, public_id sub) ✓ (1–4, 6), BE layout ✓, error contract ✓ (4, exercised in 6–9), payload cap ✓ (8), contract doc ✓ (10), testing rules ✓ (no live SMS/AI anywhere). Non-goals (refresh, rate limiting, DELETE /me, export, adherence) correctly absent. FE bindings/store = separate follow-up plan, stated up top.
- **Placeholder scan:** none — every class and test is complete code.
- **Type consistency:** `UserRow(long, UUID, byte[])`, `ConsentDto(String, boolean, OffsetDateTime)`, `PayloadCodec.encrypt(JsonNode, byte[])`, repository signatures match across Tasks 6→9 call sites.
