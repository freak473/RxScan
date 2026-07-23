package com.rxscan.backend.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * App-layer envelope crypto for the consumer plane (tech design §6.H / §7.1).
 * Phone: AES-GCM under the master key + keyed-HMAC blind index for login lookup.
 * Payloads: AES-GCM under a per-user DEK; the DEK is wrapped by the master key.
 * Keys are SHA-256-derived from configured secret strings; swapping to a real
 * KMS later touches only this class (spec: "KMS swap later touches only CryptoService").
 */
@Service
public class CryptoService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int IV_LEN = 12;      // GCM standard nonce
    private static final int TAG_BITS = 128;

    private final byte[] masterKey;
    private final byte[] blindIdxKey;

    public CryptoService(@Value("${rxscan.crypto.master-key}") String masterSecret,
                         @Value("${rxscan.crypto.blind-idx-key}") String blindIdxSecret) {
        this.masterKey = sha256(masterSecret);
        this.blindIdxKey = sha256(blindIdxSecret);
    }

    /** Keyed HMAC-SHA256 of the normalized phone — the login lookup key (no plaintext phone column). */
    public byte[] blindIndex(String phone) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(blindIdxKey, "HmacSHA256"));
            return mac.doFinal(phone.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("blind index failed", e);
        }
    }

    public byte[] newDek() {
        byte[] dek = new byte[32];
        RANDOM.nextBytes(dek);
        return dek;
    }

    public byte[] wrapDek(byte[] dek)        { return encrypt(dek, masterKey); }
    public byte[] unwrapDek(byte[] wrapped)  { return decrypt(wrapped, masterKey); }
    public byte[] encryptWithMaster(byte[] plain) { return encrypt(plain, masterKey); }
    public byte[] decryptWithMaster(byte[] blob)  { return decrypt(blob, masterKey); }

    /** AES-256-GCM; output = 12-byte IV || ciphertext+tag. */
    public byte[] encrypt(byte[] plain, byte[] key) {
        try {
            byte[] iv = new byte[IV_LEN];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain);
            byte[] out = new byte[IV_LEN + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(ct, 0, out, IV_LEN, ct.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("encrypt failed", e);
        }
    }

    public byte[] decrypt(byte[] blob, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(blob, 0, IV_LEN)));
            return cipher.doFinal(Arrays.copyOfRange(blob, IV_LEN, blob.length));
        } catch (Exception e) {
            throw new IllegalStateException("decrypt failed (wrong key or tampered data)", e);
        }
    }

    private static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
