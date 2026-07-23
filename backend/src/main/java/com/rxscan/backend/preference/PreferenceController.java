package com.rxscan.backend.preference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rxscan.backend.auth.ApiException;
import com.rxscan.backend.auth.CurrentUser;
import com.rxscan.backend.auth.PayloadCodec;
import com.rxscan.backend.auth.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** FE-owned preferences blob (meal times, toggles). Everything the FE store holds reaches the BE after login. */
@RestController
@RequestMapping("/v1/me/preferences")
public class PreferenceController {

    record PutBody(Map<String, Object> payload) {}

    private final CurrentUser currentUser;
    private final PayloadCodec codec;
    private final PreferenceRepository preferences;
    private final ObjectMapper mapper;

    public PreferenceController(CurrentUser currentUser, PayloadCodec codec, PreferenceRepository preferences,
                                ObjectMapper mapper) {
        this.currentUser = currentUser;
        this.codec = codec;
        this.preferences = preferences;
        this.mapper = mapper;
    }

    @PutMapping
    ResponseEntity<Void> put(HttpServletRequest req, @RequestBody PutBody body) {
        UserRepository.UserRow user = currentUser.require(req);
        JsonNode payloadNode = mapper.convertValue(body.payload(), JsonNode.class);
        preferences.upsert(user.userId(), codec.encrypt(payloadNode, user.dekWrapped()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    Map<String, Object> get(HttpServletRequest req) {
        UserRepository.UserRow user = currentUser.require(req);
        PreferenceRepository.PreferenceRow row = preferences.find(user.userId())
                .orElseThrow(() -> new ApiException(404, "not_found", "No preferences saved yet"));
        JsonNode payloadNode = codec.decrypt(row.payloadEnc(), user.dekWrapped());
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("payload", mapper.convertValue(payloadNode, Map.class));
        response.put("updated_at", row.updatedAt());
        return response;
    }
}
