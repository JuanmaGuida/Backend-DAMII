package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.TicketMessageRequest;
import com.reclamos.backend.dto.response.TicketMessageResponse;
import com.reclamos.backend.entity.ActivityType;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketActivity;
import com.reclamos.backend.entity.TicketMessage;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.TicketActivityRepository;
import com.reclamos.backend.repository.TicketMessageRepository;
import com.reclamos.backend.repository.TicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * Chat de tickets (Entidades V1.49 §10 "TicketMessage · mensajes públicos e
 * internos" + tabla de endpoints, fila "POST /tickets/{id}/messages").
 * Mensajes creados directamente en M2 por el ciudadano owner o por staff
 * sobre un ticket ajeno — sourceModuleId siempre "M2" acá. Complementa (no
 * reemplaza) el camino que ya existe en
 * {@code TicketStatusUpdateService#buildMessage} para publicMessage/
 * internalMessage recibidos por integración desde otros módulos; ese sigue
 * igual.
 * <p>
 * v1 a pedido explícito: sólo alta y listado, sin edición ni borrado.
 */
@Service
@RequiredArgsConstructor
public class TicketMessageService {

    private static final String SOURCE_MODULE_ID = "M2";

    private final TicketMessageRepository messageRepository;
    private final TicketRepository ticketRepository;
    private final TicketActivityRepository activityRepository;
    private final Clock clock;

    @Transactional
    public TicketMessageResponse create(UUID ticketId, TicketMessageRequest request, AuthenticatedIdentity actor) {
        Ticket ticket = findTicket(ticketId);
        ActorType authorType = requireWriteAuthority(ticket, actor, request.getVisibility());

        TicketMessage message = new TicketMessage();
        message.setTicket(ticket);
        message.setAuthorType(authorType);
        message.setAuthorId(actor.citizenId().toString());
        message.setSourceModuleId(SOURCE_MODULE_ID);
        message.setVisibility(request.getVisibility());
        message.setText(request.getText().trim());
        message = messageRepository.save(message);

        recordActivity(ticket, authorType, actor.citizenId().toString(), request.getVisibility(), message.getText());

        return toResponse(message);
    }

    @Transactional(readOnly = true)
    public List<TicketMessageResponse> list(UUID ticketId, AuthenticatedIdentity identity) {
        Ticket ticket = findTicket(ticketId);
        boolean includeInternal = requireReadAuthority(ticket, identity);
        List<TicketMessage> messages = includeInternal
                ? messageRepository.findAllByTicket_IdOrderByCreatedAtAsc(ticketId)
                : messageRepository.findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(
                        ticketId, MessageVisibility.PUBLIC);
        return messages.stream().map(this::toResponse).toList();
    }

    /**
     * Entidades V1.49 (tabla de endpoints, fila "POST /tickets/{id}/messages"):
     * "Citizen owner agrega mensajes públicos. AGENT/ADMIN pueden enviar
     * PUBLIC o INTERNAL en tickets ajenos; AREA_RESPONSIBLE sólo en tickets
     * ajenos de su areaId. Ningún rol interno puede enviar mensajes staff en
     * su propio ticket." El owner siempre actúa en capacidad CITIZEN acá (sin
     * importar su role real) y sólo puede mandar PUBLIC — mismo criterio que
     * §3.3 "la vista staff queda completamente read-only, incluso para
     * ADMIN" cuando currentUser.citizenId = ticket.citizenId.
     */
    private ActorType requireWriteAuthority(Ticket ticket, AuthenticatedIdentity actor, MessageVisibility visibility) {
        if (actor == null) {
            throw new UnauthorizedTicketOperationException();
        }
        if (isOwnTicket(ticket, actor)) {
            if (visibility != MessageVisibility.PUBLIC) {
                throw new UnauthorizedTicketOperationException();
            }
            return ActorType.CITIZEN;
        }
        if (actor.role() == ModuleRole.AGENT) {
            return ActorType.AGENT;
        }
        if (actor.role() == ModuleRole.ADMIN) {
            return ActorType.ADMIN;
        }
        if (actor.role() == ModuleRole.AREA_RESPONSIBLE) {
            if (!ticket.getResponsibleAreaId().equals(actor.areaId())) {
                throw new UnauthorizedTicketOperationException();
            }
            return ActorType.AREA_RESPONSIBLE;
        }
        // CITIZEN sobre un ticket ajeno: nunca autorizado.
        throw new UnauthorizedTicketOperationException();
    }

    /**
     * Entidades V1.49 no documenta una fila propia para GET
     * /tickets/{id}/messages (sólo el POST); esto extiende la misma matriz
     * de forma simétrica: quien puede escribir PUBLIC/INTERNAL en un ticket
     * también puede leerlos, y el owner (o cualquiera sin capacidad staff
     * sobre este ticket puntual) sólo ve PUBLIC. Devuelve true si además hay
     * que incluir los INTERNAL.
     */
    private boolean requireReadAuthority(Ticket ticket, AuthenticatedIdentity identity) {
        if (identity == null) {
            throw new UnauthorizedTicketOperationException();
        }
        if (isOwnTicket(ticket, identity)) {
            return false;
        }
        boolean isStaff = identity.role() == ModuleRole.AGENT
                || identity.role() == ModuleRole.ADMIN
                || identity.role() == ModuleRole.AREA_RESPONSIBLE;
        if (!isStaff) {
            throw new UnauthorizedTicketOperationException();
        }
        if (identity.role() == ModuleRole.AREA_RESPONSIBLE
                && !ticket.getResponsibleAreaId().equals(identity.areaId())) {
            throw new UnauthorizedTicketOperationException();
        }
        return true;
    }

    private boolean isOwnTicket(Ticket ticket, AuthenticatedIdentity identity) {
        return !ticket.isAnonymous() && ticket.getCitizenId() != null
                && ticket.getCitizenId().equals(identity.citizenId());
    }

    private Ticket findTicket(UUID ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
    }

    /**
     * Refleja el mensaje también en el timeline general de actividad
     * (Entidades V1.49 §19 "Transacción típica": modificar entidad → insertar
     * TicketActivity → insertar TicketMessage/...). PUBLIC_MESSAGE_SENT /
     * INTERNAL_MESSAGE_ADDED ya existían en ActivityType sin ningún
     * productor real — quedaron reservados para este flujo.
     */
    private void recordActivity(Ticket ticket, ActorType actorType, String actorId, MessageVisibility visibility,
                                 String text) {
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence((int) activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(visibility == MessageVisibility.PUBLIC
                ? ActivityType.PUBLIC_MESSAGE_SENT
                : ActivityType.INTERNAL_MESSAGE_ADDED);
        activity.setActorType(actorType);
        activity.setActorId(actorId);
        activity.setSourceModuleId(SOURCE_MODULE_ID);
        activity.setMessage(text);
        activity.setOccurredAt(clock.instant());
        activityRepository.save(activity);
    }

    private TicketMessageResponse toResponse(TicketMessage message) {
        return new TicketMessageResponse(
                message.getId(),
                message.getAuthorType(),
                message.getAuthorId(),
                message.getVisibility(),
                message.getText(),
                message.getCreatedAt());
    }
}
