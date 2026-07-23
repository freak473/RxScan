package com.rxscan.backend.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.rxscan.backend.consent.ConsentDto;
import com.rxscan.backend.consent.ConsentRepository;
import org.springframework.transaction.annotation.Transactional;
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
    @Transactional(transactionManager = "txManager")
    TokenResponse verify(@RequestBody OtpVerifyBody body) {
        String phone = normalize(body.phone());
        if (!otp.verify(phone, body.otp())) {
            throw new ApiException(401, "invalid_otp", "OTP did not match");
        }
        byte[] blindIdx = crypto.blindIndex(phone);
        UserRepository.UserRow user = users.findByBlindIndex(blindIdx).orElse(null);
        boolean created = false;
        if (user == null) {
            user = users.tryCreate(
                    crypto.encryptWithMaster(phone.getBytes(StandardCharsets.UTF_8)),
                    blindIdx,
                    crypto.wrapDek(crypto.newDek())).orElse(null);
            created = user != null;
            if (user == null) {   // concurrent verify created it first
                user = users.findByBlindIndex(blindIdx).orElseThrow();
            }
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
