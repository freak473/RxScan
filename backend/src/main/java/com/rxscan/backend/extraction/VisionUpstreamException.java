package com.rxscan.backend.extraction;

/**
 * The vision model call failed for any reason other than rate-limiting or "not configured" — an
 * upstream 4xx/5xx, a transport/connection error, or an unreadable response. The controller maps
 * this to 502 (bad gateway): our service is fine, the dependency it proxies to is not.
 */
public class VisionUpstreamException extends RuntimeException {

    public VisionUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }

    public VisionUpstreamException(String message) {
        super(message);
    }
}
