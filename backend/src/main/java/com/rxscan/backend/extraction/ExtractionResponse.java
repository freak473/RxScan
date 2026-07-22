package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.MedParseResult;

import java.util.List;

/** The {@code POST /extract} response body: one parsed, flag-annotated result per medicine found. */
public record ExtractionResponse(List<MedParseResult> medicines) {
}
