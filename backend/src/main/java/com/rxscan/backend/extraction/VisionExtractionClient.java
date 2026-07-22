package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.VisionMedRaw;

import java.util.List;

/**
 * A single call out to a vision model that reads a prescription image and returns one
 * {@link VisionMedRaw} per medicine it found — every field verbatim, never expanded or corrected
 * (tech-design §2.3). Implementations own the transport; callers only see the parsed reads.
 */
public interface VisionExtractionClient {

    /**
     * @param image     the prescription photo, raw bytes
     * @param mediaType the image's MIME type (e.g. {@code image/jpeg})
     * @return one entry per medicine the model found, in reading order
     */
    List<VisionMedRaw> extract(byte[] image, String mediaType);
}
