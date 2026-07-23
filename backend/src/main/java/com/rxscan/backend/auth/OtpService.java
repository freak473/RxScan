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
        Issued i = issued.get(phone);
        if (i != null && i.expiresAt().isAfter(Instant.now()) && i.code().equals(otp)) {
            issued.remove(phone);
            return true;
        }
        return false;
    }
}
