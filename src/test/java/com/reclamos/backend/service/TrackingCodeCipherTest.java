package com.reclamos.backend.service;

import com.reclamos.backend.exception.TrackingCodeUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

class TrackingCodeCipherTest {
    public static final String TEST_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private final TrackingCodeService codes = new TrackingCodeService();
    private final TrackingCodeCipher cipher = new TrackingCodeCipher(TEST_KEY, "test-v1", codes);
    private final UUID ticketId = UUID.randomUUID();
    private final UUID citizenId = UUID.randomUUID();
    private final String code = codes.generate();

    @Test
    void recoversIdenticalOriginalAcrossLogicalRestartAndPreservesHash() {
        var material = cipher.encrypt(ticketId, citizenId, code);
        assertThat(material.ciphertext()).hasSize(48);
        assertThat(material.nonce()).hasSize(12);
        assertThat(material.keyId()).isEqualTo("test-v1");
        var restarted = new TrackingCodeCipher(TEST_KEY, "test-v1", new TrackingCodeService());
        assertThat(restarted.decrypt(ticketId, citizenId, codes.hash(code), material)).isEqualTo(code);
        assertThat(codes.hash(cipher.decrypt(ticketId, citizenId, codes.hash(code), material)))
                .isEqualTo(codes.hash(code));
        assertThat(material.toString()).isEqualTo("EncryptedCode[<redacted>]");
    }

    @Test
    void independentEncryptionsUseFreshNonceAndCiphertextAndDefensiveCopies() {
        var first = cipher.encrypt(ticketId, citizenId, code);
        var second = cipher.encrypt(ticketId, citizenId, code);
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        byte[] ciphertext = first.ciphertext(), nonce = first.nonce();
        var copy = new TrackingCodeCipher.EncryptedCode(ciphertext, nonce, first.keyId());
        ciphertext[0] ^= 1; nonce[0] ^= 1;
        copy.ciphertext()[0] ^= 1; copy.nonce()[0] ^= 1;
        assertThat(cipher.decrypt(ticketId, citizenId, codes.hash(code), copy)).isEqualTo(code);
    }

    @Test
    void rejectsTamperedCiphertextTagNonceTicketOwnerKeyIdAndHash() {
        var good = cipher.encrypt(ticketId, citizenId, code);
        for (int offset : new int[]{0, 47}) {
            byte[] altered = good.ciphertext(); altered[offset] ^= 1;
            rejects(ticketId, citizenId, codes.hash(code), new TrackingCodeCipher.EncryptedCode(altered, good.nonce(), good.keyId()));
        }
        byte[] nonce = good.nonce(); nonce[0] ^= 1;
        rejects(ticketId, citizenId, codes.hash(code), new TrackingCodeCipher.EncryptedCode(good.ciphertext(), nonce, good.keyId()));
        rejects(UUID.randomUUID(), citizenId, codes.hash(code), good);
        rejects(ticketId, UUID.randomUUID(), codes.hash(code), good);
        rejects(ticketId, citizenId, codes.hash(codes.generate()), good);
        rejects(ticketId, citizenId, codes.hash(code), new TrackingCodeCipher.EncryptedCode(good.ciphertext(), good.nonce(), "other"));
        // Even changing the configured ID and stored ID together changes authenticated AAD.
        var renamed = new TrackingCodeCipher(TEST_KEY, "other", codes);
        assertThatThrownBy(() -> renamed.decrypt(ticketId, citizenId, codes.hash(code),
                new TrackingCodeCipher.EncryptedCode(good.ciphertext(), good.nonce(), "other")))
                .isInstanceOf(TrackingCodeUnavailableException.class);
    }

    @Test
    void rejectsWrongKeyWithoutLeakingMaterial() {
        byte[] wrongKey = new byte[32];
        var wrong = new TrackingCodeCipher(Base64.getEncoder().encodeToString(wrongKey), "test-v1", codes);
        var good = cipher.encrypt(ticketId, citizenId, code);
        assertThatThrownBy(() -> wrong.decrypt(ticketId, citizenId, codes.hash(code), good))
                .isInstanceOf(TrackingCodeUnavailableException.class)
                .hasMessage("El código de seguimiento no está disponible en este momento")
                .hasNoCause();
    }

    @Test
    void rejectsMissingOrInvalidMaterialAndInvalidEncryptionInputs() {
        var good = cipher.encrypt(ticketId, citizenId, code);
        rejects(null, citizenId, codes.hash(code), good);
        rejects(ticketId, null, codes.hash(code), good);
        rejects(ticketId, citizenId, null, good);
        rejects(ticketId, citizenId, codes.hash(code), null);
        rejects(ticketId, citizenId, codes.hash(code), new TrackingCodeCipher.EncryptedCode(null, good.nonce(), good.keyId()));
        rejects(ticketId, citizenId, codes.hash(code), new TrackingCodeCipher.EncryptedCode(good.ciphertext(), null, good.keyId()));
        rejects(ticketId, citizenId, codes.hash(code), new TrackingCodeCipher.EncryptedCode(new byte[47], good.nonce(), good.keyId()));
        rejects(ticketId, citizenId, codes.hash(code), new TrackingCodeCipher.EncryptedCode(good.ciphertext(), new byte[11], good.keyId()));
        assertThatThrownBy(() -> cipher.encrypt(null, citizenId, code)).isInstanceOf(TrackingCodeUnavailableException.class);
        assertThatThrownBy(() -> cipher.encrypt(ticketId, null, code)).isInstanceOf(TrackingCodeUnavailableException.class);
        assertThatThrownBy(() -> cipher.encrypt(ticketId, citizenId, "invalid")).isInstanceOf(TrackingCodeUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "secret-do-not-log", "!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="})
    void rejectsInvalidKeysWithGenericDiagnostics(String invalid) {
        assertThatThrownBy(() -> new TrackingCodeCipher(invalid, "test-v1", codes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageStartingWith("TRACKING_CODE_ENCRYPTION_KEY_BASE64 debe")
                .hasNoCause();
        assertThatThrownBy(() -> new TrackingCodeCipher(null, "test-v1", codes)).isInstanceOf(IllegalStateException.class);
        for (int size : new int[]{16, 31, 33}) {
            assertThatThrownBy(() -> new TrackingCodeCipher(Base64.getEncoder().encodeToString(new byte[size]), "test-v1", codes))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "a|b", "invalid/key", "á", "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklm"})
    void rejectsInvalidKeyIds(String invalid) {
        assertThatThrownBy(() -> new TrackingCodeCipher(TEST_KEY, invalid, codes))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("TRACKING_CODE_ENCRYPTION_KEY_ID debe");
        assertThatThrownBy(() -> new TrackingCodeCipher(TEST_KEY, null, codes)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentOperationsShareNoCipherState() throws Exception {
        var good = cipher.encrypt(ticketId, citizenId, code);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new ArrayList<java.util.concurrent.Callable<String>>();
            for (int i = 0; i < 64; i++) tasks.add(() -> {
                assertThat(cipher.decrypt(ticketId, citizenId, codes.hash(code), good)).isEqualTo(code);
                var encrypted = cipher.encrypt(ticketId, citizenId, code);
                assertThat(cipher.decrypt(ticketId, citizenId, codes.hash(code), encrypted)).isEqualTo(code);
                return Base64.getEncoder().encodeToString(encrypted.nonce());
            });
            var nonces = new HashSet<String>();
            for (var result : pool.invokeAll(tasks)) nonces.add(result.get());
            assertThat(nonces).hasSize(64);
        }
    }

    private void rejects(UUID id, UUID owner, String hash, TrackingCodeCipher.EncryptedCode material) {
        assertThatThrownBy(() -> cipher.decrypt(id, owner, hash, material)).isInstanceOf(TrackingCodeUnavailableException.class);
    }
}
