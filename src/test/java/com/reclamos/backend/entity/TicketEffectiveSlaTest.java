package com.reclamos.backend.entity;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TicketEffectiveSlaTest {
    @Test void duplicateReadsBothEffectiveDeadlinesFromMainTicket() {
        Ticket main = new Ticket();
        main.setFirstResponseDueAt(Instant.parse("2026-09-01T14:00:00Z"));
        main.setResolutionDueAt(Instant.parse("2026-09-05T14:00:00Z"));
        Ticket duplicate = new Ticket();
        duplicate.setMainTicket(main);
        duplicate.setFirstResponseDueAt(Instant.parse("2030-01-01T00:00:00Z"));
        duplicate.setResolutionDueAt(Instant.parse("2030-01-01T00:00:00Z"));

        assertEquals(main.getFirstResponseDueAt(), duplicate.getEffectiveFirstResponseDueAt());
        assertEquals(main.getResolutionDueAt(), duplicate.getEffectiveResolutionDueAt());
    }
}