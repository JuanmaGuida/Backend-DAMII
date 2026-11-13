package com.reclamos.backend.repository;

import com.reclamos.backend.entity.ExternalAttachmentReference;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ExternalAttachmentReferenceRepository extends JpaRepository<ExternalAttachmentReference, Long> {
    List<ExternalAttachmentReference> findAllByTicket_IdOrderByOccurredAtAscIdAsc(UUID ticketId);
}
