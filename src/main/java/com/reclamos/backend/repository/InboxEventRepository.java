package com.reclamos.backend.repository;

import com.reclamos.backend.entity.InboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface InboxEventRepository extends JpaRepository<InboxEvent, UUID> {
    // Serializes even the first delivery, before a row exists. Lock ends with commit/rollback.
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(:id AS text), 491))", nativeQuery = true)
    Integer lockEvent(@Param("id") UUID id);
}
