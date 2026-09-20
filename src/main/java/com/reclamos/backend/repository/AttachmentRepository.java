package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.MessageVisibility;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    @Override
    @EntityGraph(attributePaths = "ticket")
    Optional<Attachment> findById(Long id);

    List<Attachment> findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(
            UUID ticketId, MessageVisibility visibility);

    List<Attachment> findAllByTicket_IdOrderByCreatedAtAsc(UUID ticketId);
}
