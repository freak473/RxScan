package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.MedParseResult;
import com.rxscan.backend.extraction.parse.MedicationParser;

import java.util.List;

/**
 * The end-to-end extraction pipeline: one vision call, then the deterministic parser over each
 * medicine it read. The vision client is optional — deployments without a configured model (no
 * API key) still boot; asking this service to extract in that state fails loudly and specifically
 * rather than silently returning nothing.
 */
public final class ExtractionService {

    private static final String VISION_NOT_CONFIGURED =
            "Vision model not configured — no rxscan.vision.api-key/GEMINI_API_KEY is set";

    private final VisionExtractionClient client;
    private final MedicationParser parser;

    public ExtractionService(VisionExtractionClient client, MedicationParser parser) {
        this.client = client;
        this.parser = parser;
    }

    public List<MedParseResult> extract(byte[] image, String mediaType) {
        if (client == null) {
            throw new VisionUnavailableException(VISION_NOT_CONFIGURED);
        }
        return client.extract(image, mediaType).stream().map(parser::parse).toList();
    }
}
