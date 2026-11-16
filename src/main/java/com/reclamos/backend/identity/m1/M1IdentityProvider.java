package com.reclamos.backend.identity.m1;

import com.reclamos.backend.exception.IdentityAccessDeniedException;
import com.reclamos.backend.exception.IdentityProviderException;
import com.reclamos.backend.identity.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "app.identity.provider", havingValue = "M1")
public class M1IdentityProvider implements IdentityProvider {
    private final M1AccessTokenVerifier verifier;
    private final RestClient client;

    public M1IdentityProvider(M1AccessTokenVerifier verifier, @Qualifier("m1RestClient") RestClient client) {
        this.verifier = verifier;
        this.client = client;
    }

    @Override
    public Optional<ExternalAuthenticatedSession> authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) return Optional.empty();
        try {
            ResponseEntity<Map> response = client.post().uri("/auth/usuario/login")
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(new LoginPayload(username, password)).retrieve().toEntity(Map.class);
            if (response.getStatusCode().value() != 200 || response.getBody() == null) throw protocol();
            var access = session(response.getHeaders());
            Map<?, ?> body = response.getBody();
            ExternalIdentityProfile identity = access.profile();
            if (!Boolean.TRUE.equals(body.get("auth"))
                    || !identity.actorId().equals(M1AccessTokenVerifier.uuid(body.get("actorId")))
                    || !identity.actorType().name().equals(body.get("actorType"))
                    || !Objects.equals(identity.citizenId(), body.get("citizenId") == null
                        ? null : M1AccessTokenVerifier.uuid(body.get("citizenId")))) throw protocol();
            if (body.get("uuid") != null && !identity.actorId().equals(M1AccessTokenVerifier.uuid(body.get("uuid")))) throw protocol();
            if (body.get("user_id") != null && !identity.subjectId().equals(body.get("user_id").toString())) throw protocol();
            ExternalIdentityProfile profile = new ExternalIdentityProfile(identity.subjectId(), identity.actorId(),
                    identity.actorType(), IdentitySource.M1, identity.citizenId(), text(body.get("nombre")),
                    text(body.get("apellido")), null, contact(body, "EMAIL"), phone(body), null);
            return Optional.of(new ExternalAuthenticatedSession(access.token(), access.expiresAt(), profile, access.refreshToken()));
        } catch (RestClientResponseException failure) {
            return upstreamStatus(failure);
        } catch (ResourceAccessException unavailable) {
            throw new IdentityProviderException(true);
        } catch (RestClientException | IllegalArgumentException invalid) {
            throw protocol();
        }
    }

    @Override
    public Optional<ExternalIdentityProfile> resolve(String token) {
        // JWT validation only. Profile changes are obtained on the next login, not per request.
        return verifier.verify(token).map(M1AccessTokenVerifier.VerifiedAccess::identity);
    }

    @Override
    public Optional<ExternalAuthenticatedSession> refresh(String refreshToken) {
        if (!validRefreshToken(refreshToken)) return Optional.empty();
        try {
            ResponseEntity<Void> response = client.post().uri("/auth/refresh")
                    .contentType(MediaType.APPLICATION_JSON).body(new RefreshPayload(refreshToken))
                    .retrieve().toBodilessEntity();
            if (response.getStatusCode().value() != 204) throw protocol();
            return Optional.of(session(response.getHeaders()));
        } catch (RestClientResponseException failure) {
            return upstreamStatus(failure);
        } catch (ResourceAccessException unavailable) {
            throw new IdentityProviderException(true);
        } catch (RestClientException invalid) {
            throw protocol();
        }
    }

    private ExternalAuthenticatedSession session(HttpHeaders headers) {
        String authorization = singleHeader(headers, HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) throw protocol();
        String token = authorization.substring(7).trim();
        var access = verifier.verify(token).orElseThrow(M1IdentityProvider::protocol);
        String refresh = singleHeader(headers, "X-Refresh-Token");
        if (!validRefreshToken(refresh)) throw protocol();
        return new ExternalAuthenticatedSession(token, access.expiresAt(), access.identity(), refresh);
    }

    private Optional<ExternalAuthenticatedSession> upstreamStatus(RestClientResponseException failure) {
        int status = failure.getStatusCode().value();
        if (status == 401) return Optional.empty();
        if (status == 403) throw new IdentityAccessDeniedException();
        if (status == 429 || status >= 500) throw new IdentityProviderException(true);
        throw protocol();
    }

    private static String singleHeader(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        return values != null && values.size() == 1 ? values.getFirst() : null;
    }

    public static boolean validRefreshToken(String token) {
        return token != null && !token.isEmpty() && token.length() <= 3800 && token.matches("[A-Za-z0-9._~-]+");
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static String contact(Map<?, ?> body, String type) {
        if (body.get("contactos") instanceof List<?> contacts) {
            for (Object contact : contacts) {
                if (contact instanceof Map<?, ?> item && type.equals(item.get("tipo"))
                        && Boolean.TRUE.equals(item.get("verificado")) && text(item.get("valor")) != null) {
                    return text(item.get("valor"));
                }
            }
        }
        return null;
    }

    private static String phone(Map<?, ?> body) {
        String value = contact(body, "PHONE");
        return value == null ? contact(body, "TELEFONO") : value;
    }

    private static IdentityProviderException protocol() { return new IdentityProviderException(false); }

    private record LoginPayload(String gmail, String password) {
        @Override public String toString() { return "M1LoginPayload[REDACTED]"; }
    }

    private record RefreshPayload(String refreshToken) {
        @Override public String toString() { return "M1RefreshPayload[REDACTED]"; }
    }
}
