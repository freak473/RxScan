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
