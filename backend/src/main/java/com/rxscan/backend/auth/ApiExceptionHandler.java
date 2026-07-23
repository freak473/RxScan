package com.rxscan.backend.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status)
                .body(Map.of("error", Map.of("code", e.code, "message", e.getMessage())));
    }

    @ExceptionHandler(OtpDeliveryException.class)
    ResponseEntity<Map<String, Object>> otpDelivery(OtpDeliveryException e) {
        log.error("OTP delivery failed", e);
        return ResponseEntity.status(503)
                .body(Map.of("error", Map.of("code", "otp_delivery_failed", "message", "Could not send OTP — try again")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(422)
                .body(Map.of("error", Map.of("code", "invalid_payload", "message", "request body is not valid")));
    }

    // Controllers (e.g. ExtractionController) throw this directly for request-validation
    // failures with their own status/reason. It's not a local @ExceptionHandler, so without
    // this it would otherwise be swallowed by the Exception catch-all below — preserve
    // Spring's default status-only handling instead of turning it into a 500.
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Void> responseStatus(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unhandled(Exception e) {
        log.error("unhandled server error", e);
        return ResponseEntity.status(500)
                .body(Map.of("error", Map.of("code", "internal_error", "message", "Something went wrong")));
    }
}
