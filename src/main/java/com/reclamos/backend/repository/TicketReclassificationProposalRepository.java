package com.reclamos.backend.repository;

import com.reclamos.backend.entity.TicketReclassificationProposal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** All mutations acquire the Ticket lock first, including answers and expiration. */
public interface TicketReclassificationProposalRepository extends JpaRepository<TicketReclassificationProposal, Long> {
    @Query("select p from TicketReclassificationProposal p where p.ticket.id = :ticketId "
            + "and p.status in ('PROPOSED','WAITING_INFORMATION','READY')")
    Optional<TicketReclassificationProposal> findOpen(UUID ticketId);
    Optional<TicketReclassificationProposal> findByIdAndTicket_Id(Long id, UUID ticketId);
    Optional<TicketReclassificationProposal> findByInformationRequestId(UUID informationRequestId);
    List<TicketReclassificationProposal> findAllByTicket_IdOrderByIdAsc(UUID ticketId);
}
