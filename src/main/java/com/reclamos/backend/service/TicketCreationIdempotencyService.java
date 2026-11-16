package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.IdempotencyConflictException;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.repository.TicketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** PostgreSQL arbitrates requests across processes; never commits a reservation separately. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TicketCreationIdempotencyService {
    private final JdbcTemplate db;
    private final ObjectMapper mapper;
    private final TicketRepository tickets;
    private final TrackingCodeCipher cipher;
    private final SecretKeySpec fingerprintKey;

    public TicketCreationIdempotencyService(JdbcTemplate db, ObjectMapper mapper, TicketRepository tickets,
            TrackingCodeCipher cipher, @Value("${app.tracking.encryption.key-base64}") String encodedKey) {
        this.db = db;
        this.mapper = mapper;
        this.tickets = tickets;
        this.cipher = cipher;
        // Separate cryptographic purpose from AES-GCM and from other HMAC uses.
        byte[] master = Base64.getDecoder().decode(encodedKey);
        try {
            Mac derivation = Mac.getInstance("HmacSHA256");
            derivation.init(new SecretKeySpec(master, "HmacSHA256"));
            this.fingerprintKey = new SecretKeySpec(derivation.doFinal(
                    "M2:ticket-creation-idempotency:v1".getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 unavailable");
        } finally {
            Arrays.fill(master, (byte) 0);
        }
    }

    public void validateKey(String key) {
        if (key == null || !key.matches("[!-~]{1,200}")) {
            throw new InvalidTicketRequestException(
                    "Idempotency-Key debe contener entre 1 y 200 caracteres ASCII visibles, sin espacios");
        }
    }

    public Reservation reserve(String key, AuthenticatedIdentity identity, CreateTicketRequest request,
                               List<AttachmentService.ValidatedAttachment> evidence) {
        validateKey(key);
        String scope = identity == null ? "anonymous" : "citizen:" + identity.citizenId();
        String keyHash = keyHash(key);
        List<?> files = evidence.stream().map(file -> Map.of(
                "fileName", file.fileName(), "contentType", file.contentType(),
                "sizeBytes", file.sizeBytes(), "sha256", file.contentDigest())).toList();
        // Recursively order objects; array order and string whitespace remain significant.
        Object content = canonical(mapper.convertValue(request, Map.class));
        String requestHash = hmac("request", mapper.writeValueAsBytes(List.of(content, canonical(files))));
        int inserted = db.update("""
                INSERT INTO ticket_creation_idempotency(identity_scope,key_hash,request_hash)
                VALUES (?,?,?) ON CONFLICT (identity_scope,key_hash) DO NOTHING
                """, scope, keyHash, requestHash);
        if (inserted == 1) return new Reservation(scope, keyHash, null, null);

        // ON CONFLICT waits for the competing transaction. A new READ COMMITTED statement
        // sees its committed result; if it rolled back, our INSERT succeeded instead.
        return db.queryForObject("""
                SELECT request_hash,ticket_id,public_id FROM ticket_creation_idempotency
                WHERE identity_scope=? AND key_hash=?
                """, (row, index) -> {
            if (!requestHash.equals(row.getString("request_hash"))) throw new IdempotencyConflictException();
            UUID ticketId = row.getObject("ticket_id", UUID.class);
            if (ticketId == null) throw new IllegalStateException("Incomplete idempotency reservation");
            return new Reservation(scope, keyHash, ticketId, row.getString("public_id"));
        }, scope, keyHash);
    }

    public void complete(Reservation reservation, UUID ticketId, String publicId) {
        int updated = db.update("""
                UPDATE ticket_creation_idempotency SET ticket_id=?,public_id=?
                WHERE identity_scope=? AND key_hash=? AND ticket_id IS NULL
                """, ticketId, publicId, reservation.scope(), reservation.keyHash());
        if (updated != 1) throw new IllegalStateException("Idempotency reservation could not be completed");
    }

    public CreateTicketResponse replay(Reservation reservation, AuthenticatedIdentity identity) {
        String trackingCode = null;
        if (identity != null) {
            var ticket = tickets.findByIdAndCitizenIdAndAnonymousFalse(reservation.ticketId(), identity.citizenId())
                    .orElseThrow(() -> new ResourceNotFoundException("Ticket no encontrado"));
            trackingCode = cipher.decrypt(ticket.getId(), identity.citizenId(), ticket.getTrackingCodeHash(),
                    new TrackingCodeCipher.EncryptedCode(ticket.getTrackingCodeCiphertext(),
                            ticket.getTrackingCodeNonce(), ticket.getTrackingCodeKeyId()));
        }
        // Anonymous credentials are one-time: preserve the resource, never replay secrets.
        return new CreateTicketResponse(reservation.ticketId(), reservation.publicId(), trackingCode,
                TicketStatus.REGISTERED, null);
    }

    private String keyHash(String key) {
        try {
            // Stable lookup even if an operator misconfigures the encryption key: the
            // request fingerprint will conflict instead of silently creating a new ticket.
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private String hmac(String purpose, byte[] content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(fingerprintKey);
            mac.update((purpose + "\u0000").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(content));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 unavailable");
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(key.toString(), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(this::canonical).toList();
        if (value instanceof Number number) return new BigDecimal(number.toString()).stripTrailingZeros();
        return value;
    }

    public record Reservation(String scope, String keyHash, UUID ticketId, String publicId) {
        @Override public String toString() { return "Reservation[<redacted>]"; }
    }
}
