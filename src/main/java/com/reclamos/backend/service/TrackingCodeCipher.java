package com.reclamos.backend.service;

import com.reclamos.backend.exception.TrackingCodeUnavailableException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

/** Fixed v1 format: 32 UTF-8 bytes, 96-bit nonce, 128-bit authentication tag. */
public final class TrackingCodeCipher {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String KEY_ID_PATTERN = "[A-Za-z0-9._-]{1,64}";
    private final SecretKeySpec key;
    private final String keyId;
    private final TrackingCodeService trackingCodes;

    public TrackingCodeCipher(String encodedKey, String keyId, TrackingCodeService trackingCodes) {
        byte[] decoded = decodeKey(encodedKey);
        try {
            if (keyId == null || !keyId.matches(KEY_ID_PATTERN)) {
                throw new IllegalStateException("TRACKING_CODE_ENCRYPTION_KEY_ID debe ser un identificador válido de 1 a 64 caracteres");
            }
            this.key = new SecretKeySpec(decoded, "AES");
            this.keyId = keyId;
            this.trackingCodes = trackingCodes;
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private static byte[] decodeKey(String encoded) {
        if (encoded != null && encoded.length() == 44) {
            try {
                byte[] decoded = Base64.getDecoder().decode(encoded);
                if (decoded.length == 32 && Base64.getEncoder().encodeToString(decoded).equals(encoded)) {
                    return decoded;
                }
                Arrays.fill(decoded, (byte) 0);
            } catch (IllegalArgumentException ignored) {
                // Never attach the rejected value or decoding exception to startup diagnostics.
            }
        }
        throw new IllegalStateException("TRACKING_CODE_ENCRYPTION_KEY_BASE64 debe codificar exactamente 32 bytes en Base64 canónico");
    }

    public EncryptedCode encrypt(UUID ticketId, UUID citizenId, String code) {
        if (ticketId == null || citizenId == null || !trackingCodes.isValid(code)) {
            throw new TrackingCodeUnavailableException();
        }
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad(ticketId, citizenId));
            return new EncryptedCode(cipher.doFinal(code.getBytes(StandardCharsets.UTF_8)), nonce, keyId);
        } catch (GeneralSecurityException exception) {
            throw new TrackingCodeUnavailableException();
        }
    }

    public String decrypt(UUID ticketId, UUID citizenId, String expectedHash, EncryptedCode encrypted) {
        if (ticketId == null || citizenId == null || expectedHash == null || encrypted == null
                || !keyId.equals(encrypted.keyId()) || encrypted.ciphertext == null || encrypted.nonce == null
                || encrypted.ciphertext.length != 48 || encrypted.nonce.length != 12) {
            throw new TrackingCodeUnavailableException();
        }
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, encrypted.nonce));
            cipher.updateAAD(aad(ticketId, citizenId));
            plaintext = cipher.doFinal(encrypted.ciphertext);
            String code = new String(plaintext, StandardCharsets.UTF_8);
            if (!trackingCodes.isValid(code) || !MessageDigest.isEqual(
                    expectedHash.getBytes(StandardCharsets.UTF_8),
                    trackingCodes.hash(code).getBytes(StandardCharsets.UTF_8))) {
                throw new TrackingCodeUnavailableException();
            }
            return code;
        } catch (GeneralSecurityException exception) {
            throw new TrackingCodeUnavailableException();
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private byte[] aad(UUID ticketId, UUID citizenId) {
        return ("M2:tracking-code:v1|" + ticketId + "|" + citizenId + "|" + keyId)
                .getBytes(StandardCharsets.UTF_8);
    }

    public record EncryptedCode(byte[] ciphertext, byte[] nonce, String keyId) {
        public EncryptedCode {
            ciphertext = ciphertext == null ? null : ciphertext.clone();
            nonce = nonce == null ? null : nonce.clone();
        }

        @Override public byte[] ciphertext() { return ciphertext == null ? null : ciphertext.clone(); }
        @Override public byte[] nonce() { return nonce == null ? null : nonce.clone(); }
        @Override public String toString() { return "EncryptedCode[<redacted>]"; }
    }
}
