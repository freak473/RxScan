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
