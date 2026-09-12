package com.reclamos.backend.repository;

import com.reclamos.backend.entity.SlaType;
import com.reclamos.backend.entity.TicketSla;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketSlaRepository extends JpaRepository<TicketSla, Long> {
    Optional<TicketSla> findFirstByTicket_IdAndSlaTypeOrderByCycleNumberDesc(UUID ticketId, SlaType slaType);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TicketSla s where s.id = :id")
    Optional<TicketSla> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TicketSla s where s.ticket.id = :ticketId and s.slaType = :slaType " +
            "and s.completedAt is null")
    Optional<TicketSla> findActiveForUpdate(@Param("ticketId") UUID ticketId,
                                            @Param("slaType") SlaType slaType);

    @Query("select max(s.cycleNumber) from TicketSla s where s.ticket.id = :ticketId and s.slaType = :slaType")
    Optional<Integer> findMaxCycleNumber(@Param("ticketId") UUID ticketId,
                                         @Param("slaType") SlaType slaType);

    @Query("select new com.reclamos.backend.repository.TicketSlaCandidate(s.ticket.id, s.id) " +
            "from TicketSla s where s.slaType = com.reclamos.backend.entity.SlaType.RESOLUTION " +
            "and s.completedAt is null and s.ticket.mainTicket is null and s.ticket.currentStatus in " +
            "(com.reclamos.backend.entity.TicketStatus.REGISTERED, " +
            "com.reclamos.backend.entity.TicketStatus.IN_REVIEW, " +
            "com.reclamos.backend.entity.TicketStatus.ROUTED, " +
            "com.reclamos.backend.entity.TicketStatus.IN_PROGRESS, " +
            "com.reclamos.backend.entity.TicketStatus.PENDING_INFORMATION) and (" +
            "(s.status = com.reclamos.backend.entity.SlaStatus.RUNNING and s.nearDueAt <= :now) or " +
            "(s.status in (com.reclamos.backend.entity.SlaStatus.RUNNING, " +
            "com.reclamos.backend.entity.SlaStatus.NEAR_DUE) and s.dueAt <= :now))")
    List<TicketSlaCandidate> findPendingResolutionMilestones(@Param("now") Instant now);
}
