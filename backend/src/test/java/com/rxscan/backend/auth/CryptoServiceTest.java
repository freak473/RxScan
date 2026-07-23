package com.rxscan.backend.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoServiceTest {

    CryptoService crypto = new CryptoService("test-master", "test-blind");

    @Test
    void blindIndexIsDeterministicAndKeyed() {
        assertThat(crypto.blindIndex("+919876543210")).isEqualTo(crypto.blindIndex("+919876543210"));
        assertThat(crypto.blindIndex("+919876543210")).isNotEqualTo(crypto.blindIndex("+919876543211"));
        assertThat(new CryptoService("test-master", "other-key").blindIndex("+919876543210"))
                .isNotEqualTo(crypto.blindIndex("+919876543210"));
    }

    @Test
    void dekWrapRoundTrip() {
        byte[] dek = crypto.newDek();
        assertThat(dek).hasSize(32);
        assertThat(crypto.unwrapDek(crypto.wrapDek(dek))).isEqualTo(dek);
    }

    @Test
    void encryptRoundTripAndNonDeterminism() {
        byte[] dek = crypto.newDek();
        byte[] plain = "{\"schema\":1}".getBytes(StandardCharsets.UTF_8);
        byte[] blob = crypto.encrypt(plain, dek);
        assertThat(crypto.decrypt(blob, dek)).isEqualTo(plain);
        assertThat(blob).isNotEqualTo(crypto.encrypt(plain, dek)); // fresh IV each call
        assertThat(new String(blob, StandardCharsets.ISO_8859_1)).doesNotContain("schema");
    }

    @Test
    void tamperedCiphertextFails() {
        byte[] dek = crypto.newDek();
        byte[] blob = crypto.encrypt("secret".getBytes(StandardCharsets.UTF_8), dek);
        blob[blob.length - 1] ^= 1;
        byte[] tampered = blob;
        assertThatThrownBy(() -> crypto.decrypt(tampered, dek)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void masterRoundTrip() {
        byte[] phone = "+919876543210".getBytes(StandardCharsets.UTF_8);
        assertThat(crypto.decryptWithMaster(crypto.encryptWithMaster(phone))).isEqualTo(phone);
    }
}
