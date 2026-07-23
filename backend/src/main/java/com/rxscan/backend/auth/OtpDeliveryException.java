package com.rxscan.backend.auth;

/** SMS vendor failure → 503 to the client (retryable). */
public class OtpDeliveryException extends RuntimeException {
    public OtpDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
