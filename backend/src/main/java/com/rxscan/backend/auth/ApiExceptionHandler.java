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
