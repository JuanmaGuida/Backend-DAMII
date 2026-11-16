package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.MessageVisibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    @Override
    @EntityGraph(attributePaths = "ticket")
    Optional<Attachment> findById(Long id);

    @Query("select a from Attachment a where a.ticket.id = :ticketId and a.visibility = :visibility "
            + "and a.reclassificationProposalId is null order by a.createdAt")
    List<Attachment> findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(
            UUID ticketId, MessageVisibility visibility);

    @Query("select a from Attachment a where a.ticket.id = :ticketId and a.reclassificationProposalId is null order by a.createdAt")
    List<Attachment> findAllByTicket_IdOrderByCreatedAtAsc(UUID ticketId);

    List<Attachment> findAllByReclassificationProposalIdOrderByCreatedAtAsc(Long proposalId);
}
