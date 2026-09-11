package com.reclamos.backend.repository;

import com.reclamos.backend.entity.InformationRequest;
import com.reclamos.backend.entity.InformationRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InformationRequestRepository extends JpaRepository<InformationRequest, UUID> {
    boolean existsByTicketIdAndStatus(UUID ticketId, InformationRequestStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ir from InformationRequest ir where ir.ticket.id = :ticketId and ir.status = :status")
    Optional<InformationRequest> findByTicketIdAndStatusForUpdate(
            UUID ticketId, InformationRequestStatus status);

    @Query("select ir.id as requestId, ir.ticket.id as ticketId from InformationRequest ir "
            + "where ir.status = :status and ir.dueAt <= :dueAt")
    List<ExpirationCandidate> findExpirationCandidates(InformationRequestStatus status, Instant dueAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ir from InformationRequest ir where ir.id = :requestId and ir.ticket.id = :ticketId")
    Optional<InformationRequest> findByIdAndTicketIdForUpdate(UUID requestId, UUID ticketId);

    interface ExpirationCandidate {
        UUID getRequestId();
        UUID getTicketId();
    }
}
