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
