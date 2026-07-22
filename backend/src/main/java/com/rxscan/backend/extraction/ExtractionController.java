package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.MedParseResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code POST /extract} — accepts one prescription photo, runs the extraction pipeline
 * ({@link ExtractionService}), and returns the parsed, flag-annotated read per medicine.
 *
 * <p>Request-level validation (empty / unsupported type / too large) happens here, before the
 * image ever reaches the vision model. A vision model that isn't configured for this deployment
 * maps to 503 ({@link VisionUnavailableException}) rather than a generic 500.
 */
@RestController
public class ExtractionController {

    private static final String EXTRACT_PATH = "/extract";
    private static final String PARAM_IMAGE = "image";

    private static final String MEDIA_TYPE_JPEG = "image/jpeg";
    private static final String MEDIA_TYPE_PNG = "image/png";
    private static final String MEDIA_TYPE_WEBP = "image/webp";
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of(MEDIA_TYPE_JPEG, MEDIA_TYPE_PNG, MEDIA_TYPE_WEBP);

    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024; // 10MB

    private static final String ERROR_EMPTY_IMAGE = "image must not be empty";
    private static final String ERROR_UNSUPPORTED_TYPE =
            "Unsupported image type; expected one of " + ALLOWED_CONTENT_TYPES;
    private static final String ERROR_TOO_LARGE = "image exceeds the 10MB limit";
    private static final String ERROR_VISION_UNAVAILABLE = "Vision model not configured";
    private static final String ERROR_VISION_RATE_LIMITED =
            "Vision model is busy right now — please try again shortly";
    private static final String ERROR_VISION_UPSTREAM = "Vision model request failed";
    private static final String RESPONSE_KEY_MESSAGE = "message";

    private final ExtractionService extractionService;

    public ExtractionController(ExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @PostMapping(path = EXTRACT_PATH, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExtractionResponse extract(@RequestParam(PARAM_IMAGE) MultipartFile image) {
        validate(image);

        List<MedParseResult> medicines;
        try {
            medicines = extractionService.extract(image.getBytes(), image.getContentType());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_EMPTY_IMAGE, e);
        }
        return new ExtractionResponse(medicines);
    }

    private void validate(MultipartFile image) {
        if (image.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ERROR_EMPTY_IMAGE);
        }
        if (!ALLOWED_CONTENT_TYPES.contains(image.getContentType())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ERROR_UNSUPPORTED_TYPE);
        }
        if (image.getSize() > MAX_IMAGE_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, ERROR_TOO_LARGE);
        }
    }

    @ExceptionHandler(VisionUnavailableException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleVisionUnavailable(VisionUnavailableException e) {
        return Map.of(RESPONSE_KEY_MESSAGE, ERROR_VISION_UNAVAILABLE);
    }

    @ExceptionHandler(VisionRateLimitedException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> handleVisionRateLimited(VisionRateLimitedException e) {
        return Map.of(RESPONSE_KEY_MESSAGE, ERROR_VISION_RATE_LIMITED);
    }

    @ExceptionHandler(VisionUpstreamException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public Map<String, String> handleVisionUpstream(VisionUpstreamException e) {
        return Map.of(RESPONSE_KEY_MESSAGE, ERROR_VISION_UPSTREAM);
    }
}
