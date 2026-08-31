package com.reclamos.backend.dto.auth;

public record LoginRequest(String username, String password) {
    public boolean hasRequiredFields() {
        return username != null && !username.isBlank()
                && password != null && !password.isBlank();
    }

    @Override
    public String toString() {
        return "LoginRequest[username=[REDACTED], password=[REDACTED]]";
    }
}
