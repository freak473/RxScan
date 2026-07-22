package com.rxscan.backend.extraction;

/**
 * Thrown when extraction is requested but no {@link VisionExtractionClient} bean is configured
 * (no API key present). Distinct from a failed vision call — this means the feature is simply
 * not turned on in this environment.
 */
public class VisionUnavailableException extends RuntimeException {

    public VisionUnavailableException(String message) {
        super(message);
    }
}
