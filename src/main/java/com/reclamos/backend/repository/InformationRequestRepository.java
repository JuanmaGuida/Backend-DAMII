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
    Optional<InformationRequest> findByTicketIdAndStatus(UUID ticketId, InformationRequestStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select ir from InformationRequest ir where ir.status = :status and ir.dueAt <= :dueAt")
    List<InformationRequest> findByStatusAndDueAtLessThanEqual(InformationRequestStatus status, Instant dueAt);
}