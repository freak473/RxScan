package com.rxscan.backend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Opaque-payload envelope: the server stores FE-owned JSON encrypted under the
 * user's DEK and NEVER parses it (spec invariant). 256 KB cap.
 */
@Component
public class PayloadCodec {

    static final int MAX_BYTES = 256 * 1024;

    private final CryptoService crypto;
    private final ObjectMapper mapper;

    public PayloadCodec(CryptoService crypto, ObjectMapper mapper) {
        this.crypto = crypto;
        this.mapper = mapper;
    }

    public byte[] encrypt(JsonNode payload, byte[] dekWrapped) {
        if (payload == null || payload.isNull()) {
            throw new ApiException(422, "invalid_payload", "payload is required");
        }
        byte[] plain;
        try {
            plain = mapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new ApiException(422, "invalid_payload", "payload is not valid JSON");
        }
        if (plain.length > MAX_BYTES) {
            throw new ApiException(413, "payload_too_large", "payload exceeds 256 KB");
        }
        return crypto.encrypt(plain, crypto.unwrapDek(dekWrapped));
    }

    public JsonNode decrypt(byte[] blob, byte[] dekWrapped) {
        try {
            return mapper.readTree(crypto.decrypt(blob, crypto.unwrapDek(dekWrapped)));
        } catch (Exception e) {
            throw new IllegalStateException("stored payload unreadable", e);
        }
    }
}
