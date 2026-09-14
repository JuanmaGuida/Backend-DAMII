package com.reclamos.backend.repository;

import java.util.UUID;

public record TicketSlaCandidate(UUID ticketId, Long slaId) {
}
