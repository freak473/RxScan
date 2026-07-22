package com.rxscan.backend.extraction;

/**
 * The vision model rejected the call because of rate-limiting / quota exhaustion (HTTP 429). This
 * is transient, so the controller maps it to 503 (retry later) rather than a generic 500 — the
 * client (and ultimately the app) can surface a "try again shortly" state instead of a hard error.
 */
public class VisionRateLimitedException extends RuntimeException {

    public VisionRateLimitedException(String message, Throwable cause) {
        super(message, cause);
    }

    public VisionRateLimitedException(String message) {
        super(message);
    }
}
