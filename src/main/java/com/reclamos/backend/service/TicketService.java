package com.reclamos.backend.service;

import com.reclamos.backend.dto.TicketFilter;
import com.reclamos.backend.dto.TicketResponse;
import com.reclamos.backend.dto.request.CancelTicketRequest;
import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.*;
import com.reclamos.backend.dto.response.AnonymousContactResponse;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.dto.response.StaffTicketDetailResponse;
import com.reclamos.backend.dto.response.TicketActivityResponse;
import com.reclamos.backend.dto.response.TicketAttachmentResponse;
import com.reclamos.backend.dto.response.TicketDetailResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.EvidenceRequiredException;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.TicketStateConflictException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TicketService {
    /**
     * "M2" identifica gestión propia de Atención Ciudadana (Eventos v1.6 §2.1):
     * un ticket de esa "área" inicia gestión propia en IN_PROGRESS y nunca
     * genera ROUTED; los identificados sí actualizan la proyección de M1.
     */
    private static final String SELF_MANAGED_AREA_ID = "M2";

    /**
     * Whitelist de propiedades de Ticket habilitadas para {@code ?sort=}.
     * Se valida acá, antes de tocar el repository, en lugar de dejar que
     * Spring Data traduzca el Sort contra la entidad recién al ejecutar la
     * query (lo que devolvería un error interno del framework sin manejar
     * para un campo inexistente). Quedan afuera relaciones (requestType,
     * mainTicket), el mapa formData y trackingCodeHash (dato sensible). Si
     * el equipo quiere habilitar más/menos campos, este es el único lugar
     * que hay que tocar.
     */
    private static final Set<String> SORTABLE_TICKET_PROPERTIES = Set.of(
            "id", "publicId", "ticketType", "responsibleAreaId", "assignedAgent.id",
            "summary", "currentStatus", "currentPriority", "estimatedAffectedCount",
            "escalated", "escalationReasonCode", "escalatedAt", "reopenCount",
            "statusChangedAt", "resolutionConfirmationDueAt", "classificationFinalizedAt",
            "currentProgress", "createdAt", "updatedAt"
    );

    /** Estados habilitados para la cancelación administrativa de tickets ajenos. */
    private static final Set<TicketStatus> ADMIN_CANCELLABLE_STATUSES = Set.of(
            TicketStatus.REGISTERED, TicketStatus.IN_REVIEW, TicketStatus.PENDING_INFORMATION);

    private final RequestTypeRepository requestTypeRepository;
    private final TicketRepository ticketRepository;
    private final TicketActivityRepository activityRepository;
    private final AttachmentRepository attachmentRepository;
    private final TicketLocationRepository locationRepository;
    private final NeighborhoodRepository neighborhoodRepository;
    private final TicketCancellationRepository cancellationRepository;
    private final TicketSlaService ticketSlaService;
    private final InformationRequestService informationRequestService;
    private final InformationRequestProjectionService informationRequestProjectionService;
    private final FormValidationService formValidationService;
    private final RiskCalculationService riskCalculationService;
    private final TicketOutboxService ticketOutboxService;
    private final TrackingCodeService trackingCodeService;
    private final TicketPublicIdGenerator publicIdGenerator;
    private final AttachmentService attachmentService;
    private final AnonymousTicketCredentialService anonymousTicketCredentialService;
    private final AnonymousContactValidator anonymousContactValidator;
    private final ModuleUserRepository moduleUserRepository;
    private final TicketLabelRepository ticketLabelRepository;
    private final Clock clock;

    @Transactional
    public CreateTicketResponse create(CreateTicketRequest request, AuthenticatedIdentity identity,
                                       MultipartFile[] evidence) {
        boolean anonymous = identity == null;
        if (!anonymous && request.containsAnonymousData()) {
            throw new InvalidTicketRequestException(
                    "Los datos de acceso o contacto anónimo no aplican a un ticket identificado");
        }
        RequestType requestType = requestTypeRepository.findByIdForUpdate(request.requestTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Request Type no encontrado"));
        if (!requestType.isActive()) {
            throw new InvalidTicketRequestException("El Request Type seleccionado está inactivo");
        }
        if (anonymous && !requestType.isAllowsAnonymous()) {
            throw new InvalidTicketRequestException("El Request Type seleccionado no admite creación anónima");
        }
        ResolvedForm resolvedForm = formValidationService.resolveAndValidate(requestType, request.formData());
        RiskAssessment assessment = riskCalculationService.calculateRisk(requestType, resolvedForm);
        Risk risk = assessment.calculatedRisk();
        List<AttachmentService.ValidatedAttachment> validatedAttachments = attachmentService.validate(evidence);
        if ((risk == Risk.HIGH || risk == Risk.CRITICAL) && validatedAttachments.isEmpty()) {
            throw new EvidenceRequiredException();
        }
        validateLocation(requestType, request.location());

        AnonymousContactValidator.ValidatedContact anonymousContact = anonymous
                ? anonymousContactValidator.validate(request.anonymousContact())
                : null;
        AnonymousTicketCredentialService.CredentialMaterial anonymousCredential = anonymous
                ? anonymousTicketCredentialService.prepare(request.anonymousAccessPassword())
                : null;

        String trackingCode;
        String trackingHash;
        do {
            trackingCode = trackingCodeService.generate();
            trackingHash = trackingCodeService.hash(trackingCode);
        } while (ticketRepository.existsByTrackingCodeHash(trackingHash));

        Instant now = clock.instant();
        String publicId = publicIdGenerator.generate(now);
        Ticket ticket = new Ticket();
        ticket.setPublicId(publicId);
        ticket.setTrackingCodeHash(trackingHash);
        ticket.setCitizenId(anonymous ? null : identity.citizenId());
        ticket.setAnonymous(anonymous);
        ticket.setAnonymousAccessPasswordHash(anonymous ? anonymousCredential.passwordHash() : null);
        ticket.setAnonymousContactChannel(anonymousContact == null ? null : anonymousContact.channel());
        ticket.setAnonymousContactValue(anonymousContact == null ? null : anonymousContact.value());
        ticket.setRequestType(requestType);
        ticket.setFormTemplateId(resolvedForm.formTemplateId());
        ticket.setTicketType(requestType.getTicketType());
        ticket.setResponsibleAreaId(requestType.getResponsibleAreaId());
        ticket.setSummary(request.summary());
        ticket.setDescription(request.description());
        ticket.setFormData(new HashMap<>(resolvedForm.formData()));
        ticket.setCurrentStatus(TicketStatus.REGISTERED);
        ticket.setCurrentPriority(max(requestType.getMinimumPriority(), risk));
        ticket.setCreatedAt(now);
        ticket.setEstimatedAffectedCount(0);
        ticket.setReopenCount(0);
        ticket.setEscalated(false);
        ticket.setPublic(false);
        ticket.setStatusChangedAt(now);
        ticket = ticketRepository.save(ticket);
        ticketRepository.flush();
        ticketSlaService.startFirstResponseCycle(ticket, now);
        Optional<TicketSla> resolutionSla = ticketSlaService.startInitialResolutionCycle(ticket, now);

        List<Attachment> storedAttachments = validatedAttachments.isEmpty()
                ? List.of()
                : attachmentService.storeForTicket(ticket, identity, validatedAttachments, now);

        TicketLocation location = null;
        if (request.location() != null) {
            location = locationRepository.save(toLocation(ticket, request.location()));
        }
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(1);
        activity.setActionType(ActivityType.TICKET_CREATED);
        activity.setPreviousStatus(null);
        activity.setNewStatus(TicketStatus.REGISTERED);
        activity.setActorType(ActorType.CITIZEN);
        activity.setActorId(anonymous ? null : identity.citizenId().toString());
        activity.setOccurredAt(now);
        activityRepository.save(activity);
        activateCriticalEscalationIfNeeded(ticket, now);
        ticketOutboxService.ticketCreated(ticket, location, storedAttachments,
                resolutionSla.map(TicketSla::getDueAt).orElse(null));
        ticketRepository.flush();
        return new CreateTicketResponse(ticket.getId(), ticket.getPublicId(), trackingCode,
                TicketStatus.REGISTERED, anonymous ? anonymousCredential.generatedPassword() : null);
    }

    /**
     * Story 3.2 (BE - Endpoint de transición de estados del ticket): la única
     * transición que corresponde a esta story es la toma de ticket por un agente,
     * REGISTERED -&gt; IN_REVIEW. Las demás transiciones (ROUTED, RESOLVED, etc.) son
     * stories aparte más adelante en el plan y no se tocan acá.
     */
    @Transactional
    public TicketResponse startReview(UUID ticketId, AuthenticatedIdentity actor) {
        Ticket ticket = loadForUpdate(ticketId);
        requireTriageAuthority(ticket, actor);

        if (ticket.getCurrentStatus() != TicketStatus.REGISTERED) {
            throw new TicketStateConflictException(
                    "El ticket está en estado " + ticket.getCurrentStatus()
                            + " y no puede tomarse a revisión: sólo se puede tomar un ticket REGISTERED");
        }

        TicketStatus previousStatus = ticket.getCurrentStatus();
        Instant reviewedAt = clock.instant();
        ticketSlaService.completeFirstResponseCycle(ticket, reviewedAt);
        ticket.setCurrentStatus(TicketStatus.IN_REVIEW);
        ticket.setStatusChangedAt(reviewedAt);
        // Entidades V1.49 §"CONVENCIÓN DE IDENTIFICADORES DE ACTOR": a diferencia
        // de la mayoría de los actorId (que guardan citizenId), Ticket.assignedAgentId
        // es una FK real a ModuleUser.id, reservada para relaciones puramente
        // internas de M2. Por eso acá se resuelve el ModuleUser del agente
        // autenticado (por su citizenId) en lugar de guardar actor.subjectId()
        // directamente. Decisión (a confirmar con el equipo): si el agente
        // autenticado no tiene un ModuleUser registrado, se corta con 404 en
        // lugar de dejar el ticket sin asignar silenciosamente — un agente
        // autenticado que no existe como ModuleUser es un estado inconsistente
        // que conviene visibilizar, no absorber.
        if (ticket.getAssignedAgent() == null) {
            ModuleUser agent = moduleUserRepository.findByCitizenId(actor.citizenId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "El agente autenticado no está registrado como ModuleUser"));
            ticket.setAssignedAgent(agent);
        }
        ticketRepository.save(ticket);

        recordActivity(ticket, ActivityType.REVIEW_STARTED, previousStatus, TicketStatus.IN_REVIEW,
                actor, null, null, null, reviewedAt);
        ticketOutboxService.statusChanged(ticket, null, reviewedAt);

        return toResponse(ticket, locationRepository.findByTicket_Id(ticketId).orElse(null));
    }

    /**
     * Story 3.2 (BE - Endpoint de corrección de clasificación). Sólo se permite
     * mientras el ticket está en su primera IN_REVIEW y todavía no se finalizó la
     * clasificación (Entidades V1.49 §4.1 / Guía funcional complementaria M2
     * V1.09 §6).
     * <p>
     * Recalcula responsibleAreaId, estimatedAffectedCount, formTemplateId,
     * formData, currentPriority y el SLA inicial (Guía funcional §5 /
     * Decisiones #2: "si RequestType se corrige durante la primera IN_REVIEW,
     * se recalcula el SLA inicial desde createdAt con la nueva
     * clasificación").
     * <p>
     * Prioridad: a diferencia del eventual recálculo periódico definido como
     * regla futura (donde, por la "REGLA DE EVOLUCIÓN" de la Guía funcional
     * §3, currentPriority nunca bajaría), una corrección de RequestType durante la primera IN_REVIEW
     * todavía forma parte de la clasificación inicial y sí puede recalcularla
     * libremente. Como formData se resetea a {} más abajo, el riesgo
     * recalculado da exactamente newRequestType.baseRisk sin incrementos, así
     * que se usa baseRisk directamente. La prioridad resultante se floorea
     * únicamente contra newRequestType.minimumPriority (mismo patrón que
     * create()), pudiendo quedar por debajo de la anterior.
     */
    @Transactional
    public TicketResponse correctClassification(UUID ticketId, Long newRequestTypeId, AuthenticatedIdentity actor) {
        Ticket ticket = loadForUpdate(ticketId);
        requireTriageAuthority(ticket, actor);

        if (ticket.getCurrentStatus() != TicketStatus.IN_REVIEW || ticket.getClassificationFinalizedAt() != null) {
            throw new TicketStateConflictException(
                    "La clasificación sólo puede corregirse durante la primera revisión del ticket, "
                            + "antes de derivarlo, iniciar gestión o vincularlo como duplicado");
        }

        RequestType newRequestType = requestTypeRepository.findByIdForUpdate(newRequestTypeId)
                .filter(RequestType::isActive)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "El Request Type solicitado no existe o está inactivo"));

        RequestType previousRequestType = ticket.getRequestType();
        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        if (Objects.equals(previousRequestType.getId(), newRequestType.getId())) {
            return toResponse(ticket, location);
        }
        Priority previousPriority = ticket.getCurrentPriority();

        int estimatedAffectedCount = estimateAffectedCount(newRequestType, location);
        Priority newPriority = max(newRequestType.getMinimumPriority(), newRequestType.getBaseRisk());
        FormTemplate newFormTemplate = formValidationService.resolveActiveTemplate(newRequestType);

        ticket.setRequestType(newRequestType);
        ticket.setTicketType(newRequestType.getTicketType());
        ticket.setResponsibleAreaId(newRequestType.getResponsibleAreaId());
        ticket.setEstimatedAffectedCount(estimatedAffectedCount);
        ticket.setCurrentPriority(newPriority);
        ticket.setFormTemplateId(newFormTemplate == null ? null : newFormTemplate.getId());
        // Las respuestas del formData anterior están validadas contra el
        // FormTemplate del RequestType viejo y no corresponden necesariamente
        // a los campos del nuevo, así que se resetea a {} (formTemplateId ya
        // queda registrado con la nueva plantilla más arriba).
        ticket.setFormData(new HashMap<>());
        // Guía funcional §5 / Decisiones #2: la corrección de clasificación
        // durante la primera IN_REVIEW recalcula el SLA inicial desde
        // createdAt (no desde "ahora") con la nueva prioridad.
        ticketRepository.save(ticket);

        String message = "RequestType corregido de '" + previousRequestType.getCode()
                + "' a '" + newRequestType.getCode() + "' durante la revisión inicial";
        Instant reclassifiedAt = clock.instant();
        recordActivity(ticket, ActivityType.REQUEST_TYPE_CHANGED, ticket.getCurrentStatus(), ticket.getCurrentStatus(),
                actor, previousPriority, newPriority, message, reclassifiedAt);
        activateCriticalEscalationIfNeeded(ticket, reclassifiedAt);
        Optional<TicketSla> resolutionSla = ticketSlaService.recalculateInitialResolutionCycle(ticket, reclassifiedAt);
        ticketOutboxService.contentUpdated(ticket,
                resolutionSla.map(TicketSla::getDueAt).orElse(null), reclassifiedAt);

        return toResponse(ticket, location, resolutionSla.orElse(null));
    }

    /**
     * Finaliza la clasificación: inicia gestión propia M2 en IN_PROGRESS o
     * deriva una gestión externa a ROUTED y publica su snapshot inicial.
     * <p>
     * INTERPRETACIÓN DE "SELECCIONAR UN ÁREA VÁLIDA" (AC): este endpoint no
     * recibe un área por parámetro. El área ya quedó fijada en
     * responsibleAreaId durante la clasificación (Story 3.2 / RequestType), así
     * que acá "seleccionar" se interpreta como confirmar/validar esa área ya
     * asignada, no como un input nuevo del agente. Si el equipo necesita que el
     * agente pueda cambiar el área en el momento de derivar, este método hay
     * que extenderlo para recibir un areaId explícito.
     * <p>
     * No recalcula el SLA: ya quedó fijado en create() o en
     * correctClassification() y nada cambia entre la primera IN_REVIEW y esta
     * derivación que lo afecte.
     */
    @Transactional
    public TicketResponse routeToArea(UUID ticketId, AuthenticatedIdentity actor) {
        Ticket ticket = loadForUpdate(ticketId);
        requireTriageAuthority(ticket, actor);

        if (ticket.getCurrentStatus() != TicketStatus.IN_REVIEW) {
            throw new TicketStateConflictException(
                    "El ticket está en estado " + ticket.getCurrentStatus()
                            + " y no puede derivarse: sólo se puede derivar un ticket IN_REVIEW");
        }
        if (ticket.getResponsibleAreaId() == null || ticket.getResponsibleAreaId().isBlank()) {
            throw new TicketStateConflictException(
                    "El ticket no tiene un área responsable válida asignada; no puede derivarse");
        }
        if (ticket.getRequestType() == null) {
            throw new TicketStateConflictException("El ticket no tiene una clasificación válida; no puede iniciar gestión");
        }

        TicketStatus previousStatus = ticket.getCurrentStatus();
        Instant now = clock.instant();
        boolean selfManaged = SELF_MANAGED_AREA_ID.equalsIgnoreCase(ticket.getResponsibleAreaId());
        TicketStatus nextStatus = selfManaged ? TicketStatus.IN_PROGRESS : TicketStatus.ROUTED;
        ticket.setCurrentStatus(nextStatus);
        ticket.setStatusChangedAt(now);
        // classificationFinalizedAt se fija sólo la primera vez que el ticket
        // sale de IN_REVIEW hacia gestión (Entidades §4.1): a partir de acá,
        // correctClassification() ya no acepta correcciones ni siquiera después
        // de un ROUTED -> RETURNED -> IN_REVIEW posterior.
        if (ticket.getClassificationFinalizedAt() == null) {
            ticket.setClassificationFinalizedAt(now);
        }
        ticketRepository.save(ticket);

        ActivityType activityType = selfManaged ? ActivityType.STATE_CHANGED : ActivityType.ROUTED;
        String message = selfManaged
                ? "Comenzó la gestión propia de Atención Ciudadana"
                : "Derivado al área responsable '" + ticket.getResponsibleAreaId() + "'";
        recordActivity(ticket, activityType, previousStatus, nextStatus, actor, null, null, message, now);

        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        TicketSla resolutionSla = ticketSlaService.findLatestResolutionCycle(ticket).orElse(null);

        // La gestión externa recibe el snapshot ROUTED. En gestión propia sólo
        // se proyecta STATUS_CHANGED para identificados; el helper omite anónimos.
        if (selfManaged) {
            ticketOutboxService.statusChanged(ticket, null, now);
        } else {
            ticketOutboxService.routed(ticket, location,
                    resolutionSla == null ? null : resolutionSla.getDueAt(), now);
        }

        return toResponse(ticket, location, resolutionSla);
    }

    /**
     * POST /tickets/{id}/cancel. El propietario actúa con capacidad ciudadana
     * independientemente de su role y sólo puede cancelar en REGISTERED.
     * AGENT/ADMIN conservan la cancelación administrativa de tickets ajenos
     * en {@link #ADMIN_CANCELLABLE_STATUSES}. ROUTED/IN_PROGRESS se cancelan
     * por el flujo de integración (updateTicketStatus/REJECTED), no por acá.
     * La cancelación de un ticket DUPLICATE (Entidades §14: "se
     * agrega la transición DUPLICATE -&gt; CANCELLED por
     * WITHDRAWN_BY_CITIZEN") queda pendiente de Story 7.2 (Sprint 5):
     * DUPLICATE todavía no es un estado alcanzable en el sistema.
     */
    @Transactional
    public TicketResponse cancelTicket(UUID ticketId, CancelTicketRequest request, AuthenticatedIdentity actor) {
        Ticket ticket = loadForUpdate(ticketId);
        boolean isOwnTicket = actor != null && !ticket.isAnonymous() && ticket.getCitizenId() != null
                && ticket.getCitizenId().equals(actor.citizenId());
        requireCancelAuthority(actor, isOwnTicket);

        ActorType actorType = isOwnTicket ? ActorType.CITIZEN
                : (actor.role() == ModuleRole.ADMIN ? ActorType.ADMIN : ActorType.AGENT);
        return cancelTicket(ticket, request, actorType, actor.citizenId().toString(), isOwnTicket, true);
    }

    @Transactional
    public TicketResponse cancelAnonymousTicket(UUID ticketId, CancelTicketRequest request) {
        Ticket ticket = loadForUpdate(ticketId);
        if (!ticket.isAnonymous() || ticket.getCitizenId() != null) {
            throw new UnauthorizedTicketOperationException();
        }
        return cancelTicket(ticket, request, ActorType.CITIZEN, null, true, false);
    }

    private TicketResponse cancelTicket(Ticket ticket, CancelTicketRequest request, ActorType actorType,
                                        String actorId, boolean ownerAction, boolean publishCancellation) {
        if (ownerAction && ticket.getCurrentStatus() != TicketStatus.REGISTERED) {
            throw new TicketStateConflictException(
                    "El ticket está en estado " + ticket.getCurrentStatus()
                            + " y el propietario sólo puede cancelarlo directamente en REGISTERED");
        }
        if (!ownerAction && !ADMIN_CANCELLABLE_STATUSES.contains(ticket.getCurrentStatus())) {
            throw new TicketStateConflictException(
                    "El ticket está en estado " + ticket.getCurrentStatus()
                            + " y no admite cancelación administrativa por este endpoint"
                            + " (estados permitidos: REGISTERED, IN_REVIEW o PENDING_INFORMATION)");
        }

        Instant now = clock.instant();

        TicketCancellation cancellation = new TicketCancellation();
        cancellation.setTicket(ticket);
        cancellation.setReasonCode(request.getReasonCode());
        cancellation.setPublicMessage(request.getPublicMessage());
        cancellation.setInternalMessage(request.getInternalMessage());
        cancellation.setCancelledByType(actorType);
        cancellation.setCancelledById(actorId);
        cancellation.setCancelledByModuleId(SELF_MANAGED_AREA_ID);
        cancellation.setCancelledAt(now);
        cancellationRepository.save(cancellation);

        TicketStatus previousStatus = ticket.getCurrentStatus();
        if (previousStatus == TicketStatus.PENDING_INFORMATION) {
            informationRequestService.cancelPendingBecauseTicketTerminated(ticket);
        }
        ticketSlaService.terminateActiveCycles(ticket, now);
        ticket.setCurrentStatus(TicketStatus.CANCELLED);
        ticket.setStatusChangedAt(now);
        ticketRepository.save(ticket);

        recordCancellationActivity(ticket, previousStatus, actorType, actorId, request.getReasonCode(),
                request.getPublicMessage() != null ? request.getPublicMessage() : request.getInternalMessage(), now);

        // La cancelación directa del propietario anónimo ocurre únicamente en
        // REGISTERED, antes de ROUTED, por lo que no existe un consumidor externo.
        // Los flujos identificados y administrativos conservan su política previa.
        if (publishCancellation) {
            ticketOutboxService.cancelled(ticket, request.getReasonCode(), request.getPublicMessage(), true, now);
        }

        return toResponse(ticket, locationRepository.findByTicket_Id(ticket.getId()).orElse(null));
    }

    private void requireCancelAuthority(AuthenticatedIdentity actor, boolean isOwnTicket) {
        if (actor == null) {
            throw new UnauthorizedTicketOperationException();
        }
        boolean isStaff = actor.role() == ModuleRole.AGENT || actor.role() == ModuleRole.ADMIN;
        if (!isOwnTicket && !isStaff) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    private void recordCancellationActivity(Ticket ticket, TicketStatus previousStatus, ActorType actorType,
                                             String actorId, CancellationReasonCode reasonCode,
                                             String message, Instant occurredAt) {
        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence((int) activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(ActivityType.CANCELLED);
        activity.setPreviousStatus(previousStatus);
        activity.setNewStatus(TicketStatus.CANCELLED);
        activity.setActorType(actorType);
        activity.setActorId(actorId);
        activity.setSourceModuleId(SELF_MANAGED_AREA_ID);
        activity.setReasonCode(reasonCode.name());
        activity.setMessage(message);
        activity.setOccurredAt(occurredAt);
        activityRepository.save(activity);
    }

    /**
     * Story 3.1 (BE - Endpoint de listado con filtros por categoría, prioridad,
     * barrio/rol y estado). "Rol" del work item se interpretó como
     * responsibleAreaId — ver el javadoc de TicketFilter.
     */
    @Transactional(readOnly = true)
    public Page<TicketResponse> listTickets(TicketFilter filter, Pageable pageable) {
        validateSort(pageable.getSort());
        Specification<Ticket> specification = TicketSpecifications.build(filter);
        Page<Ticket> page = ticketRepository.findAll(specification, pageable);

        List<UUID> ticketIds = page.getContent().stream().map(Ticket::getId).toList();
        Map<UUID, TicketLocation> locationsByTicket = locationRepository
                .findAllByTicket_IdIn(ticketIds).stream()
                .collect(Collectors.toMap(location -> location.getTicket().getId(), Function.identity()));
        Map<UUID, TicketSla> latestFirstResponseByTicket =
                ticketSlaService.findLatestFirstResponseCycles(page.getContent());
        Map<UUID, TicketSla> latestResolutionByTicket =
                ticketSlaService.findLatestResolutionCycles(page.getContent());

        return mapPage(page, locationsByTicket, latestFirstResponseByTicket, latestResolutionByTicket);
    }

    /**
     * GET /me/tickets (Entidades V1.49 §"Autenticación, roles y vistas"):
     * cualquier usuario autenticado vía M1 conserva capacidades ciudadanas
     * base sobre sus propios tickets. A diferencia de listTickets (la
     * bandeja staff de la Story 3.1, restringida por rol en
     * SecurityConfiguration), acá no hay scoping por rol ni área: siempre
     * se filtra por el citizenId de quien pregunta.
     */
    @Transactional(readOnly = true)
    public Page<TicketResponse> listMyTickets(AuthenticatedIdentity identity, Pageable pageable) {
        validateSort(pageable.getSort());
        Page<Ticket> page = ticketRepository.findByCitizenId(identity.citizenId(), pageable);

        List<UUID> ticketIds = page.getContent().stream().map(Ticket::getId).toList();
        Map<UUID, TicketLocation> locationsByTicket = locationRepository
                .findAllByTicket_IdIn(ticketIds).stream()
                .collect(Collectors.toMap(location -> location.getTicket().getId(), Function.identity()));

        Map<UUID, TicketSla> latestFirstResponseByTicket =
                ticketSlaService.findLatestFirstResponseCycles(page.getContent());
        Map<UUID, TicketSla> latestResolutionByTicket =
                ticketSlaService.findLatestResolutionCycles(page.getContent());
        return mapPage(page, locationsByTicket, latestFirstResponseByTicket, latestResolutionByTicket);
    }

    /**
     * GET /tickets/{id} (Entidades V1.49 §"Autenticación, roles y vistas":
     * "Ciudadano owner | Consultar detalle ciudadano autorizado."). Un
     * ticket anónimo no tiene citizenId, así que nunca es "propio" de
     * ningún autenticado acá; se consulta por POST /tracking/access, no por
     * este endpoint.
     */
    @Transactional(readOnly = true)
    public TicketDetailResponse getById(UUID ticketId, AuthenticatedIdentity identity) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
        requireOwner(ticket, identity);
        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        return toDetailResponse(ticket, location, false);
    }

    private void requireOwner(Ticket ticket, AuthenticatedIdentity identity) {
        if (identity == null || ticket.isAnonymous() || ticket.getCitizenId() == null
                || !ticket.getCitizenId().equals(identity.citizenId())) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    /**
     * GET /staff/tickets/{id} (Guía funcional M2 §7, tabla de roles + §7.1):
     * detalle staff. AGENT y ADMIN acceden a cualquier ticket ajeno sin
     * restricción de área. AREA_RESPONSIBLE sólo accede a tickets de su
     * propia areaId, salvo que el ticket sea suyo como ciudadano (§7.1:
     * "si el usuario interno es owner... puede ver información interna por
     * su rol"), en cuyo caso también puede verlo aunque sea de otra área.
     * La restricción de "toda acción staff queda bloqueada" en ticket propio
     * no aplica acá porque este endpoint es de sólo lectura.
     */
    @Transactional(readOnly = true)
    public StaffTicketDetailResponse getStaffDetail(UUID ticketId, AuthenticatedIdentity identity) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
        requireStaffAccess(ticket, identity);
        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        StaffTicketDetailResponse detail = toDetailResponse(
                ticket, location, true, new StaffTicketDetailResponse());
        if (ticket.isAnonymous()
                && (identity.role() == ModuleRole.AGENT || identity.role() == ModuleRole.ADMIN)
                && ticket.getAnonymousContactChannel() != null
                && ticket.getAnonymousContactValue() != null) {
            detail.setAnonymousContact(new AnonymousContactResponse(
                    ticket.getAnonymousContactChannel(), ticket.getAnonymousContactValue()));
        }
        return detail;
    }

    private void requireStaffAccess(Ticket ticket, AuthenticatedIdentity identity) {
        if (identity == null || (identity.role() != ModuleRole.AGENT
                && identity.role() != ModuleRole.ADMIN
                && identity.role() != ModuleRole.AREA_RESPONSIBLE)) {
            throw new UnauthorizedTicketOperationException();
        }
        boolean isOwnTicket = !ticket.isAnonymous() && ticket.getCitizenId() != null
                && ticket.getCitizenId().equals(identity.citizenId());
        if (identity.role() == ModuleRole.AREA_RESPONSIBLE && !isOwnTicket
                && !ticket.getResponsibleAreaId().equals(identity.areaId())) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    /**
     * Story 2.3 (Enforcement de permisos por rol en backend): autorización
     * para las acciones de triage (startReview/correctClassification/
     * routeToArea). El filtro de rol (sólo AGENT/ADMIN llegan hasta acá; ver
     * SecurityConfiguration — AREA_RESPONSIBLE queda afuera de estas tres
     * acciones porque la Guía funcional M2 §7 limita su capacidad staff a
     * "leer y enviar mensajes PUBLIC/INTERNAL", sin triage, ni siquiera sobre
     * tickets de su propia área) ya se resolvió antes de entrar acá. Lo que
     * falta y no se puede expresar en el filtro es el bloqueo de "acción
     * staff en ticket propio": Entidades V1.49 §3.3 dice que si
     * currentUser.citizenId = ticket.citizenId, "la vista staff queda
     * completamente read-only, incluso para ADMIN" — ningún AGENT/ADMIN
     * puede triagear su propio ticket.
     */
    void requireTriageAuthority(Ticket ticket, AuthenticatedIdentity actor) {
        if (actor == null || (actor.role() != ModuleRole.AGENT && actor.role() != ModuleRole.ADMIN)) {
            throw new UnauthorizedTicketOperationException();
        }
        boolean isOwnTicket = !ticket.isAnonymous() && ticket.getCitizenId() != null
                && ticket.getCitizenId().equals(actor.citizenId());
        if (isOwnTicket) {
            throw new UnauthorizedTicketOperationException();
        }
    }

    /**
     * GET /staff/tickets/{id}/citizen-view (Guía funcional M2 §7.1): "misma
     * proyección visible al ciudadano", pero consultada desde el contexto
     * staff — no cambia la identidad de quien pregunta ni suplanta al
     * propietario. El control de acceso de entrada es el mismo que
     * getStaffDetail (AGENT/ADMIN cualquier ticket ajeno; AREA_RESPONSIBLE
     * sólo su areaId o su propio ticket); la Guía aclara "en ticket ajeno es
     * read-only", pero eso ya lo garantiza que este endpoint sea un GET.
     * <p>
     * Comparte autorización con getStaffDetail, pero usa la proyección pública
     * del detalle: sólo adjuntos PUBLIC y actividades sanitizadas, aun cuando
     * quien consulta tenga un rol staff.
     */
    @Transactional(readOnly = true)
    public TicketDetailResponse getStaffCitizenView(UUID ticketId, AuthenticatedIdentity identity) {
        Ticket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
        requireStaffAccess(ticket, identity);
        TicketLocation location = locationRepository.findByTicket_Id(ticketId).orElse(null);
        return toDetailResponse(ticket, location, false);
    }

    private void validateSort(Sort sort) {
        for (Sort.Order order : sort) {
            if (!SORTABLE_TICKET_PROPERTIES.contains(order.getProperty())) {
                throw new InvalidTicketRequestException(
                        "El campo de ordenamiento '" + order.getProperty() + "' no es válido");
            }
        }
    }

    private Ticket loadForUpdate(UUID ticketId) {
        return ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new ResourceNotFoundException("El ticket solicitado no existe"));
    }

    private int estimateAffectedCount(RequestType requestType, TicketLocation location) {
        if (location == null || location.getNeighborhood() == null
                || requestType.getAffectedPopulationFactor() == null) {
            return 0;
        }
        BigDecimal population = BigDecimal.valueOf(location.getNeighborhood().getPopulation());
        return population.multiply(requestType.getAffectedPopulationFactor())
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    private void recordActivity(Ticket ticket, ActivityType actionType, TicketStatus previousStatus,
                                 TicketStatus newStatus, AuthenticatedIdentity actor,
                                 Priority previousPriority, Priority newPriority, String message,
                                 Instant occurredAt) {
        long nextSequence = activityRepository.countByTicketId(ticket.getId()) + 1;

        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence((int) nextSequence);
        activity.setActionType(actionType);
        activity.setPreviousStatus(previousStatus);
        activity.setNewStatus(newStatus);
        activity.setActorType(actor.role() == ModuleRole.ADMIN ? ActorType.ADMIN : ActorType.AGENT);
        activity.setActorId(actor.citizenId().toString());
        activity.setSourceModuleId(SELF_MANAGED_AREA_ID);
        activity.setPreviousPriority(previousPriority);
        activity.setNewPriority(newPriority);
        activity.setMessage(message);
        activity.setOccurredAt(occurredAt);
        activityRepository.save(activity);
    }

    /**
     * Activa una sola vez el escalamiento automático por prioridad crítica.
     * La marca es independiente del estado y la actividad representa una
     * decisión del sistema, incluso cuando la prioridad surgió de una
     * reclasificación iniciada por un agente.
     */
    private void activateCriticalEscalationIfNeeded(Ticket ticket, Instant activatedAt) {
        if (ticket.getCurrentPriority() != Priority.CRITICAL || ticket.isEscalated()) {
            return;
        }

        ticket.setEscalated(true);
        ticket.setEscalationReasonCode(EscalationReasonCode.CRITICAL_PRIORITY);
        ticket.setEscalatedAt(activatedAt);
        ticketRepository.save(ticket);

        TicketActivity activity = new TicketActivity();
        activity.setTicket(ticket);
        activity.setSequence(activityRepository.countByTicketId(ticket.getId()) + 1);
        activity.setActionType(ActivityType.ESCALATED);
        activity.setPreviousStatus(ticket.getCurrentStatus());
        activity.setNewStatus(ticket.getCurrentStatus());
        activity.setActorType(ActorType.SYSTEM);
        activity.setActorId(null);
        activity.setReasonCode(EscalationReasonCode.CRITICAL_PRIORITY.name());
        activity.setMessage("Escalamiento automático por prioridad crítica");
        activity.setOccurredAt(activatedAt);
        activityRepository.save(activity);
    }

    private TicketResponse toResponse(Ticket ticket, TicketLocation location) {
        return toResponse(ticket, location,
                ticketSlaService.findLatestFirstResponseCycle(ticket).orElse(null),
                ticketSlaService.findLatestResolutionCycle(ticket).orElse(null));
    }

    private TicketDetailResponse toDetailResponse(Ticket ticket, TicketLocation location, boolean staffView) {
        return toDetailResponse(ticket, location, staffView, new TicketDetailResponse());
    }

    private <T extends TicketDetailResponse> T toDetailResponse(
            Ticket ticket, TicketLocation location, boolean staffView, T detail) {
        TicketResponse base = toResponse(ticket, location);
        detail.setId(base.getId());
        detail.setPublicId(base.getPublicId());
        detail.setRequestTypeCode(base.getRequestTypeCode());
        detail.setRequestTypeName(base.getRequestTypeName());
        detail.setCategoryName(base.getCategoryName());
        detail.setSubcategoryName(base.getSubcategoryName());
        detail.setTicketType(base.getTicketType());
        detail.setSummary(base.getSummary());
        detail.setDescription(ticket.getDescription());
        detail.setCurrentStatus(base.getCurrentStatus());
        detail.setCurrentPriority(base.getCurrentPriority());
        detail.setResponsibleAreaId(base.getResponsibleAreaId());
        detail.setAssignedAgentId(base.getAssignedAgentId());
        detail.setAnonymous(base.isAnonymous());
        detail.setEstimatedAffectedCount(base.getEstimatedAffectedCount());
        detail.setEscalated(base.isEscalated());
        detail.setEscalationReasonCode(base.getEscalationReasonCode());
        detail.setEscalatedAt(base.getEscalatedAt());
        detail.setFirstResponseDueAt(base.getFirstResponseDueAt());
        detail.setFirstResponseNearDue(base.isFirstResponseNearDue());
        detail.setFirstResponseBreached(base.isFirstResponseBreached());
        detail.setResolutionDueAt(base.getResolutionDueAt());
        detail.setSlaNearDue(base.isSlaNearDue());
        detail.setSlaBreached(base.isSlaBreached());
        detail.setResolutionNearDueAt(base.getResolutionNearDueAt());
        detail.setNeighborhoodName(base.getNeighborhoodName());
        detail.setClassificationFinalizedAt(base.getClassificationFinalizedAt());
        detail.setStatusChangedAt(base.getStatusChangedAt());
        detail.setCreatedAt(base.getCreatedAt());
        detail.setUpdatedAt(base.getUpdatedAt());

        InformationRequestProjectionService.Projection pending =
                informationRequestProjectionService.findPending(ticket.getId());
        detail.setPendingInformationRequest(pending == null ? null : pending.citizen());
        if (detail instanceof StaffTicketDetailResponse staffDetail) {
            staffDetail.setPendingInformationRequestContext(pending == null ? null : pending.staff());
        }

        List<Attachment> attachments = staffView
                ? attachmentRepository.findAllByTicket_IdOrderByCreatedAtAsc(ticket.getId())
                : attachmentRepository.findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(
                        ticket.getId(), MessageVisibility.PUBLIC);
        detail.setAttachments(attachments.stream().map(this::toAttachmentResponse).toList());

        detail.setTicketActivities(activityRepository.findAllByTicket_IdOrderBySequenceAsc(ticket.getId()).stream()
                .filter(activity -> staffView || isCitizenVisibleActivity(activity))
                .map(activity -> toActivityResponse(activity, staffView))
                .toList());
        if (staffView) {
            detail.setLabels(ticketLabelRepository.findLabelsByTicketId(ticket.getId()).stream()
                    .map(label -> new LabelSummaryResponse(label.getId(), label.getCode(), label.getName(), label.getDescription()))
                    .toList());
        }
        return detail;
    }

    private TicketAttachmentResponse toAttachmentResponse(Attachment attachment) {
        return new TicketAttachmentResponse(attachment.getId(), attachment.getFileName(),
                attachment.getContentType(), attachment.getSizeBytes(), attachment.getVisibility(),
                attachment.getCreatedAt());
    }

    private boolean isCitizenVisibleActivity(TicketActivity activity) {
        return activity.getActionType() != ActivityType.INTERNAL_MESSAGE_ADDED
                && activity.getActionType() != ActivityType.ATTACHMENT_ADDED;
    }

    private TicketActivityResponse toActivityResponse(TicketActivity activity, boolean staffView) {
        Instant effectiveOccurredAt = activity.getOccurredAt() != null
                ? activity.getOccurredAt()
                : activity.getCreatedAt();
        return new TicketActivityResponse(
                activity.getSequence(),
                activity.getActionType(),
                activity.getPreviousStatus(),
                activity.getNewStatus(),
                effectiveOccurredAt,
                staffView || isCitizenVisibleReason(activity.getActionType()) ? activity.getReasonCode() : null,
                staffView ? activity.getActorType() : null,
                staffView ? activity.getPreviousPriority() : null,
                staffView ? activity.getNewPriority() : null,
                staffView ? activity.getMessage() : null);
    }

    private boolean isCitizenVisibleReason(ActivityType actionType) {
        return actionType == ActivityType.CANCELLED
                || actionType == ActivityType.RESOLVED
                || actionType == ActivityType.CLOSED
                || actionType == ActivityType.REOPENED;
    }

    private TicketResponse toResponse(Ticket ticket, TicketLocation location, TicketSla resolutionSla) {
        return toResponse(ticket, location,
                ticketSlaService.findLatestFirstResponseCycle(ticket).orElse(null), resolutionSla);
    }

    private TicketResponse toResponse(Ticket ticket, TicketLocation location,
                                      TicketSla firstResponseSla, TicketSla resolutionSla) {
        RequestType requestType = ticket.getRequestType();
        Subcategory subcategory = requestType.getSubcategory();
        Category category = subcategory.getCategory();

        TicketResponse response = new TicketResponse();
        response.setId(ticket.getId());
        response.setPublicId(ticket.getPublicId());
        response.setRequestTypeCode(requestType.getCode());
        response.setRequestTypeName(requestType.getName());
        response.setCategoryName(category.getName());
        response.setSubcategoryName(subcategory.getName());
        response.setTicketType(ticket.getTicketType());
        response.setSummary(ticket.getSummary());
        response.setCurrentStatus(ticket.getCurrentStatus());
        response.setCurrentPriority(ticket.getCurrentPriority());
        response.setResponsibleAreaId(ticket.getResponsibleAreaId());
        response.setAssignedAgentId(ticket.getAssignedAgent() != null
                ? String.valueOf(ticket.getAssignedAgent().getId()) : null);
        response.setAnonymous(ticket.isAnonymous());
        response.setEstimatedAffectedCount(ticket.getEstimatedAffectedCount());
        response.setEscalated(ticket.isEscalated());
        response.setEscalationReasonCode(ticket.getEscalationReasonCode());
        response.setEscalatedAt(ticket.getEscalatedAt());
        applySlaSignals(response, firstResponseSla, resolutionSla);
        if (location != null && location.getNeighborhood() != null) {
            response.setNeighborhoodId(location.getNeighborhood().getId());
            response.setNeighborhoodName(location.getNeighborhood().getName());
        }
        response.setClassificationFinalizedAt(ticket.getClassificationFinalizedAt());
        response.setStatusChangedAt(ticket.getStatusChangedAt());
        response.setCreatedAt(ticket.getCreatedAt());
        response.setUpdatedAt(ticket.getUpdatedAt());
        return response;
    }

    private void applySlaSignals(TicketResponse response, TicketSla firstResponseSla,
                                 TicketSla resolutionSla) {
        if (firstResponseSla == null) {
            response.setFirstResponseDueAt(null);
            response.setFirstResponseNearDue(false);
            response.setFirstResponseBreached(false);
        } else {
            response.setFirstResponseDueAt(firstResponseSla.getDueAt());
            response.setFirstResponseNearDue(firstResponseSla.getStatus() == SlaStatus.NEAR_DUE);
            response.setFirstResponseBreached(firstResponseSla.getStatus() == SlaStatus.BREACHED);
        }
        if (resolutionSla == null) {
            response.setResolutionDueAt(null);
            response.setSlaNearDue(false);
            response.setSlaBreached(false);
            response.setResolutionNearDueAt(null);
            return;
        }
        response.setResolutionDueAt(resolutionSla.getDueAt());
        response.setSlaNearDue(resolutionSla.getStatus() == SlaStatus.NEAR_DUE);
        response.setSlaBreached(resolutionSla.getStatus() == SlaStatus.BREACHED);
        response.setResolutionNearDueAt(resolutionSla.getNearDueAt());
    }

    private Page<TicketResponse> mapPage(Page<Ticket> page,
                                         Map<UUID, TicketLocation> locationsByTicket,
                                         Map<UUID, TicketSla> latestFirstResponseByTicket,
                                         Map<UUID, TicketSla> latestResolutionByTicket) {
        return page.map(ticket -> toResponse(ticket, locationsByTicket.get(ticket.getId()),
                latestFirstResponseByTicket.get(ticket.getId()), latestResolutionByTicket.get(ticket.getId())));
    }

    private void validateLocation(RequestType type, CreateTicketRequest.LocationData location) {
        if (type.isRequiresLocation() && location == null) {
            throw new InvalidTicketRequestException("La ubicación es obligatoria para el Request Type seleccionado");
        }
        if (location == null) return;
        boolean hasLatitude = location.latitude() != null;
        boolean hasLongitude = location.longitude() != null;
        if (hasLatitude != hasLongitude) {
            throw new InvalidTicketRequestException("La latitud y longitud deben informarse juntas");
        }
        boolean hasCoordinates = hasLatitude;
        boolean hasUsefulLocation = location.addressLine() != null && !location.addressLine().isBlank()
                || location.neighborhoodId() != null || hasCoordinates;
        if (type.isRequiresLocation() && !hasUsefulLocation) {
            throw new InvalidTicketRequestException("La ubicación es obligatoria para el Request Type seleccionado");
        }
        if (hasCoordinates && (location.latitude().doubleValue() < -90 || location.latitude().doubleValue() > 90
                || location.longitude().doubleValue() < -180 || location.longitude().doubleValue() > 180)) {
            throw new InvalidTicketRequestException("Las coordenadas de ubicación son inválidas");
        }
        if (location.neighborhoodId() != null && !neighborhoodRepository.existsById(location.neighborhoodId())) {
            throw new ResourceNotFoundException("Barrio no encontrado");
        }
    }

    private TicketLocation toLocation(Ticket ticket, CreateTicketRequest.LocationData data) {
        TicketLocation location = new TicketLocation();
        location.setTicket(ticket);
        location.setAddressLine(data.addressLine());
        location.setStreet(data.street());
        location.setStreetNumber(data.streetNumber());
        location.setLatitude(data.latitude());
        location.setLongitude(data.longitude());
        location.setReference(data.reference());
        if (data.neighborhoodId() != null) {
            location.setNeighborhood(neighborhoodRepository.getReferenceById(data.neighborhoodId()));
        }
        return location;
    }

    private Priority max(Priority minimum, Risk risk) {
        Priority fromRisk = Priority.valueOf(risk.name());
        return priorityRank(minimum) >= priorityRank(fromRisk) ? minimum : fromRisk;
    }

    private int priorityRank(Priority priority) {
        return switch (priority) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
            case CRITICAL -> 3;
        };
    }

}
