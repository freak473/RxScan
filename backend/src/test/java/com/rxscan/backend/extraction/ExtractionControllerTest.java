package com.rxscan.backend.extraction;

import com.rxscan.backend.extraction.parse.DurationType;
import com.rxscan.backend.extraction.parse.FieldName;
import com.rxscan.backend.extraction.parse.Flag;
import com.rxscan.backend.extraction.parse.FlagReason;
import com.rxscan.backend.extraction.parse.Meal;
import com.rxscan.backend.extraction.parse.MedParseResult;
import com.rxscan.backend.extraction.parse.Pattern;
import com.rxscan.backend.extraction.parse.Slots;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test: {@code ExtractionService} is mocked out, so this only exercises request
 * validation (empty/type/size) + response shaping + exception mapping. {@code @WebMvcTest} loads
 * just the MVC slice — the two-datasource {@code DataSourceConfig} is a plain {@code @Configuration}
 * outside the web-test component filter, so it is never instantiated here (no Postgres needed).
 */
@WebMvcTest(ExtractionController.class)
class ExtractionControllerTest {

    private static final String JPEG = "image/jpeg";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ExtractionService extractionService;

    @MockitoBean
    private com.rxscan.backend.auth.JwtService jwtService;

    private static MedParseResult oneMed() {
        return new MedParseResult(
                new MedParseResult.Drug("Augmentin 625 Duo", 1L, 0.95),
                new MedParseResult.Strength("625mg", 0.95),
                new MedParseResult.Frequency("1-0-1", new Slots(1, 0, 1, 0), Pattern.DAILY, 0.95),
                new MedParseResult.MealTiming(Meal.AFTER, 0.95),
                new MedParseResult.Duration(DurationType.DAYS, 5, 0.95),
                List.<Flag>of());
    }

    @Test
    void valid_jpeg_multipart_returns_200_with_parsed_medicines() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "rx.jpg", JPEG, new byte[]{1, 2, 3});
        when(extractionService.extract(any(), eq(JPEG))).thenReturn(List.of(oneMed()));

        mockMvc.perform(multipart("/extract").file(image))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.medicines[0].drug.value").value("Augmentin 625 Duo"));
    }

    @Test
    void unsupported_content_type_returns_415() throws Exception {
        MockMultipartFile file = new MockMultipartFile("image", "rx.txt", "text/plain", new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/extract").file(file))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void empty_file_returns_400() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("image", "rx.jpg", JPEG, new byte[0]);

        mockMvc.perform(multipart("/extract").file(empty))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversized_file_returns_413() throws Exception {
        byte[] tooBig = new byte[11 * 1024 * 1024]; // > 10MB
        MockMultipartFile big = new MockMultipartFile("image", "rx.jpg", JPEG, tooBig);

        mockMvc.perform(multipart("/extract").file(big))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void vision_unavailable_returns_503() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "rx.jpg", JPEG, new byte[]{1, 2, 3});
        when(extractionService.extract(any(), eq(JPEG)))
                .thenThrow(new VisionUnavailableException("Vision model not configured"));

        mockMvc.perform(multipart("/extract").file(image))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void vision_rate_limited_returns_503() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "rx.jpg", JPEG, new byte[]{1, 2, 3});
        when(extractionService.extract(any(), eq(JPEG)))
                .thenThrow(new VisionRateLimitedException("rate limited"));

        mockMvc.perform(multipart("/extract").file(image))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void vision_upstream_failure_returns_502() throws Exception {
        MockMultipartFile image = new MockMultipartFile("image", "rx.jpg", JPEG, new byte[]{1, 2, 3});
        when(extractionService.extract(any(), eq(JPEG)))
                .thenThrow(new VisionUpstreamException("upstream failed"));

        mockMvc.perform(multipart("/extract").file(image))
                .andExpect(status().isBadGateway());
    }
}
