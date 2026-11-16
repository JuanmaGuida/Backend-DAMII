package com.reclamos.backend.repository;

import com.reclamos.backend.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    @Query(value = "SELECT * FROM outbox_events WHERE status='PENDING' AND auto_publish_eligible=TRUE "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= :now) "
            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<OutboxEvent> findNextAutomaticForUpdate(@Param("now") Instant now);

    // Held until confirmation and DB commit. Other workers skip the occupied row.
    @Query(value = "SELECT * FROM outbox_events WHERE status='PENDING' AND event_id IN (:approved) "
            + "AND (next_attempt_at IS NULL OR next_attempt_at <= :now) "
            + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<OutboxEvent> findNextApprovedForUpdate(@Param("approved") Collection<UUID> approved,
                                                  @Param("now") Instant now);
}
