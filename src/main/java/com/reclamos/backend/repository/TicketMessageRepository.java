package com.reclamos.backend.repository;

import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.TicketMessage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from TicketMessage m where m.id = :id")
    Optional<TicketMessage> findByIdForUpdate(@Param("id") Long id);

    /**
     * Chat de ticket (Entidades V1.49 §10): listado completo para vistas con
     * capacidad staff (ven PUBLIC + INTERNAL).
     */
    List<TicketMessage> findAllByTicket_IdOrderByCreatedAtAsc(UUID ticketId);

    /**
     * Mismo listado pero acotado a una visibilidad — usado para el ciudadano
     * owner, que nunca debe ver mensajes INTERNAL (Eventos V1.69 §11: "Nunca
     * publicar internalMessage... El internalMessage recibido... queda sólo
     * en M2", misma regla aplicada acá a los mensajes directos).
     */
    List<TicketMessage> findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(
            UUID ticketId, MessageVisibility visibility);
}
