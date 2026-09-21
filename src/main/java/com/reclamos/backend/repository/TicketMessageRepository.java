package com.reclamos.backend.repository;

import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.TicketMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, Long> {

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
