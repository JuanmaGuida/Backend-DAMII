package com.reclamos.backend.repository;

import com.reclamos.backend.entity.InformationAttachmentRole;
import com.reclamos.backend.entity.InformationRequestAttachment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InformationRequestAttachmentRepository extends JpaRepository<InformationRequestAttachment, Long> {
    @EntityGraph(attributePaths = "attachment")
    List<InformationRequestAttachment> findAllByInformationRequest_IdAndRoleOrderByAttachment_CreatedAtAsc(
            UUID informationRequestId, InformationAttachmentRole role);
}
