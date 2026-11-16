package com.reclamos.backend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;

@Component
public class TicketPublicIdGenerator {
    private static final String NEXT_SEQUENCE_VALUE_SQL =
            "SELECT nextval('ticket_public_id_seq')";

    private final JdbcTemplate jdbcTemplate;

    public TicketPublicIdGenerator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String generate(Instant creationInstant) {
        Objects.requireNonNull(creationInstant, "creationInstant es obligatorio");
        Long sequenceValue = Objects.requireNonNull(
                jdbcTemplate.queryForObject(NEXT_SEQUENCE_VALUE_SQL, Long.class),
                "ticket_public_id_seq no devolvió un valor");
        int creationYear = creationInstant.atZone(ZoneOffset.UTC).getYear();

        return "TK-%04d-%06d".formatted(creationYear, sequenceValue);
    }
}
