package com.reclamos.backend.service;

import com.reclamos.backend.exception.InvalidTicketRequestException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class AnonymousTicketCredentialService {
    private static final int GENERATED_PASSWORD_BYTES = 24;
    private static final int MIN_PASSWORD_CHARACTERS = 8;
    private static final int MAX_BCRYPT_UTF8_BYTES = 72;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PasswordEncoder passwordEncoder;

    public AnonymousTicketCredentialService(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
    }

    public CredentialMaterial prepare(String providedPassword) {
        if (providedPassword == null) {
            String generatedPassword = generate();
            return new CredentialMaterial(passwordEncoder.encode(generatedPassword), generatedPassword);
        }
        validate(providedPassword);
        return new CredentialMaterial(passwordEncoder.encode(providedPassword), null);
    }

    public boolean matches(String rawPassword, String passwordHash) {
        return rawPassword != null && passwordHash != null && passwordEncoder.matches(rawPassword, passwordHash);
    }

    private void validate(String password) {
        if (password.isBlank()) {
            throw new InvalidTicketRequestException("La contraseña anónima no puede estar vacía");
        }
        if (password.codePointCount(0, password.length()) < MIN_PASSWORD_CHARACTERS) {
            throw new InvalidTicketRequestException("La contraseña anónima debe tener al menos 8 caracteres");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_UTF8_BYTES) {
            throw new InvalidTicketRequestException("La contraseña anónima no puede superar 72 bytes UTF-8");
        }
    }

    private String generate() {
        byte[] random = new byte[GENERATED_PASSWORD_BYTES];
        SECURE_RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    public record CredentialMaterial(String passwordHash, String generatedPassword) {
        @Override
        public String toString() {
            return "CredentialMaterial[passwordHash=<redacted>, generatedPassword=<redacted>]";
        }
    }
}
