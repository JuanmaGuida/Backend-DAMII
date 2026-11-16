package com.reclamos.backend.repository;

import com.reclamos.backend.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;

public interface TicketCancellationRequestRepository extends JpaRepository<TicketCancellationRequest, UUID> {
    boolean existsByTicket_IdAndStatus(UUID ticketId, CancellationRequestStatus status);
    List<TicketCancellationRequest> findAllByTicket_IdOrderByRequestedAtAscIdAsc(UUID ticketId);
    Optional<TicketCancellationRequest> findByIdAndTicket_Id(UUID id, UUID ticketId);
}
