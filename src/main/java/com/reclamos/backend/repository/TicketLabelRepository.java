package com.reclamos.backend.repository;

import com.reclamos.backend.entity.TicketLabel;
import com.reclamos.backend.entity.TicketLabelId;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.*;

public interface TicketLabelRepository extends JpaRepository<TicketLabel, TicketLabelId> {

    boolean existsById_LabelId(UUID labelId);

    @Query("select tl.label from TicketLabel tl where tl.id.ticketId = :ticketId order by tl.label.name, tl.label.id")
    List<com.reclamos.backend.entity.Label> findLabelsByTicketId(@Param("ticketId") UUID ticketId);

    @Modifying
    @Query(value = "insert into ticket_labels(ticket_id,label_id,source,created_by,created_at) values (:ticketId,:labelId,'MANUAL',:createdBy,:createdAt) " +
            "on conflict (ticket_id,label_id) do nothing", nativeQuery = true)
    int insertManualIfAbsent(@Param("ticketId") UUID ticketId, @Param("labelId") UUID labelId,
                             @Param("createdBy") UUID createdBy, @Param("createdAt") Instant createdAt);

    @Modifying
    @Query("delete from TicketLabel tl where tl.id.ticketId=:ticketId and tl.id.labelId=:labelId")
    int deleteAssignment(@Param("ticketId") UUID ticketId, @Param("labelId") UUID labelId);
}