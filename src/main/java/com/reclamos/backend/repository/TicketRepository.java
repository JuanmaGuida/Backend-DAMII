package com.reclamos.backend.repository;

import com.reclamos.backend.entity.Ticket;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {
    boolean existsByTrackingCodeHash(String trackingCodeHash);
    boolean existsByPublicId(String publicId);

    @EntityGraph(attributePaths = {"requestType.subcategory.category"})
    Optional<Ticket> findByTrackingCodeHash(String trackingCodeHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE) // bloquea el registro para lectura y escritura hasta que se termine la transacción
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findByIdForUpdate(UUID id);
}
