package com.rxscan.backend.prescription;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rxscan.backend.auth.ApiException;
import com.rxscan.backend.auth.CurrentUser;
import com.rxscan.backend.auth.PayloadCodec;
import com.rxscan.backend.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The confirmed prescription record — deferred save: the FE holds it in Room
 * (pendingSync) until OTP verify succeeds, then POSTs. Payload is FE-owned and
 * server-opaque (encrypted under the user's DEK; the server never parses it).
 */
@RestController
@RequestMapping("/v1/prescriptions")
public class PrescriptionController {

    record PutBody(Map<String, Object> payload) {}
    record CreatedResponse(@JsonProperty("rx_id") UUID rxId,
                           @JsonProperty("updated_at") OffsetDateTime updatedAt) {}
    record RxResponse(@JsonProperty("rx_id") UUID rxId, Map<String, Object> payload,
                      @JsonProperty("created_at") OffsetDateTime createdAt,
                      @JsonProperty("updated_at") OffsetDateTime updatedAt) {}

    private final CurrentUser currentUser;
    private final PayloadCodec codec;
    private final PrescriptionRepository prescriptions;
    private final ObjectMapper mapper;

    public PrescriptionController(CurrentUser currentUser, PayloadCodec codec,
                                  PrescriptionRepository prescriptions, ObjectMapper mapper) {
        this.currentUser = currentUser;
        this.codec = codec;
        this.prescriptions = prescriptions;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CreatedResponse create(HttpServletRequest req, @RequestBody PutBody body) {
        UserRepository.UserRow user = currentUser.require(req);
        JsonNode payloadNode = mapper.convertValue(body.payload(), JsonNode.class);
        PrescriptionRepository.Created created =
                prescriptions.insert(user.userId(), codec.encrypt(payloadNode, user.dekWrapped()));
        return new CreatedResponse(created.rxId(), created.updatedAt());
    }

    @PatchMapping("/{rxId}")
    Map<String, Object> update(HttpServletRequest req, @PathVariable UUID rxId, @RequestBody PutBody body) {
        UserRepository.UserRow user = currentUser.require(req);
        JsonNode payloadNode = mapper.convertValue(body.payload(), JsonNode.class);
        OffsetDateTime updatedAt = prescriptions
                .update(rxId, user.userId(), codec.encrypt(payloadNode, user.dekWrapped()))
                .orElseThrow(() -> new ApiException(404, "not_found", "No such prescription"));
        return Map.of("updated_at", updatedAt);
    }

    @GetMapping
    Map<String, Object> list(HttpServletRequest req,
                             @RequestParam(required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime since) {
        UserRepository.UserRow user = currentUser.require(req);
        List<RxResponse> out = prescriptions.findSince(user.userId(), since).stream()
                .map(r -> {
                    JsonNode decrypted = codec.decrypt(r.payloadEnc(), user.dekWrapped());
                    Map<String, Object> payloadMap = mapper.convertValue(decrypted, Map.class);
                    return new RxResponse(r.rxId(), payloadMap, r.createdAt(), r.updatedAt());
                })
                .toList();
        return Map.of("prescriptions", out);
    }
}
