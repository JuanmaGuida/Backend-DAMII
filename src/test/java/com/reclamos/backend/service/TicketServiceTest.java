package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.CreateTicketRequest;
import com.reclamos.backend.dto.response.CreateTicketResponse;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.*;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.util.*;
import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

    @Mock
    private RequestTypeRepository requestTypes;

    @Mock
    private TicketRepository tickets;

    @Mock
    private TicketActivityRepository activities;

    @Mock
    private TicketLocationRepository locations;

    @Mock
    private NeighborhoodRepository neighborhoods;

    @Mock
    private FormValidationService forms;

    @Mock
    private RiskCalculationService risks;

    @Spy
    private TrackingCodeService trackingCodes = new TrackingCodeService();

    @Mock
    private SlaCalculationService sla;

    private final AttachmentService attachments = mock(AttachmentService.class);

    @Mock
    private Clock clock;

    @InjectMocks
    private TicketService service;

    private RequestType requestType;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(NOW);

        lenient().when(
                sla.calculateDueAt(
                        any(),
                        any(Priority.class),
                        eq(SlaType.FIRST_RESPONSE)
                )
        ).thenReturn(Optional.empty());

        lenient().when(
                sla.calculateResolutionDueAt(
                        any(),
                        any(Priority.class),
                        any(TicketType.class)
                )
        ).thenReturn(Optional.empty());

        requestType = requestType(true);

        lenient().when(requestTypes.findById(1L))
                .thenReturn(Optional.of(requestType));

        FormTemplate template = new FormTemplate();
        template.setId(3L);
        template.setRequestType(requestType);

        lenient().when(forms.resolveAndValidate(any(), any()))
                .thenAnswer(invocation -> {
                    Map<String, Object> data = invocation.getArgument(1);

                    return new ResolvedForm(
                            template,
                            List.of(),
                            data == null ? Map.of() : data
                    );
                });

        lenient().when(tickets.save(any()))
                .thenAnswer(invocation -> {
                    Ticket ticket = invocation.getArgument(0);
                    ticket.setId(UUID.randomUUID());
                    return ticket;
                });

        when(
                attachments.validate(
                        nullable(
                                org.springframework.web.multipart.MultipartFile[].class
                        )
                )
        ).thenAnswer(invocation -> {
            org.springframework.web.multipart.MultipartFile[] files =
                    invocation.getArgument(0);

            if (files == null || files.length == 0) {
                return List.of();
            }

            return Arrays.stream(files)
                    .map(file ->
                            new AttachmentService.ValidatedAttachment(
                                    file,
                                    file.getOriginalFilename(),
                                    file.getContentType(),
                                    file.getSize()
                            )
                    )
                    .toList();
        });

        when(
                attachments.storeForTicket(
                        any(),
                        any(),
                        anyList(),
                        any()
                )
        ).thenReturn(List.of());
    }

    @Test
    void lowRiskWithoutEvidenceCreatesRegisteredServerClassifiedTicket() {
        when(
                risks.calculateRisk(
                        any(RequestType.class),
                        any(ResolvedForm.class)
                )
        ).thenReturn(new RiskAssessment(0, Risk.LOW));

        AuthenticatedIdentity citizen = identity();

        CreateTicketResponse response =
                service.create(request(), citizen, null);

        assertEquals(TicketStatus.REGISTERED, response.status());
        assertNotNull(response.trackingCode());
        assertTrue(response.publicId().matches("OP-[0-9]{10}"));

        assertThrows(
                IllegalArgumentException.class,
                () -> UUID.fromString(response.publicId())
        );

        verify(tickets).save(
                argThat(ticket ->
                        ticket.getCurrentStatus() == TicketStatus.REGISTERED
                                && ticket.getTicketType() == requestType.getTicketType()
                                && ticket.getResponsibleAreaId().equals("M6")
                                && ticket.getResponsibleAreaId()
                                .equals(requestType.getResponsibleAreaId())
                                && ticket.getFormTemplateId().equals(3L)
                                && ticket.getRequestType()
                                .getSubcategory()
                                .getCategory() != null
                                && !ticket.getTrackingCodeHash()
                                .equals(response.trackingCode())
                )
        );

        verify(activities).save(
                argThat(activity ->
                        activity.getActionType() == ActivityType.TICKET_CREATED
                                && activity.getSequence() == 1
                                && activity.getActorType() == ActorType.CITIZEN
                                && citizen.citizenId()
                                .toString()
                                .equals(activity.getActorId())
                                && !citizen.subjectId()
                                .equals(activity.getActorId())
                                && activity.getSourceModuleId() == null
                )
        );
    }

    @Test
    void mediumRiskWithoutEvidenceCreatesTicket() {
        when(
                risks.calculateRisk(
                        any(RequestType.class),
                        any(ResolvedForm.class)
                )
        ).thenReturn(new RiskAssessment(36, Risk.MEDIUM));

        assertNotNull(
                service.create(
                        request(),
                        identity(),
                        null
                ).ticketId()
        );
    }

    @Test
    void highAndCriticalRiskWithEvidenceCreateTickets() {
        MockMultipartFile evidence =
                new MockMultipartFile(
                        "evidence",
                        "photo.jpg",
                        "image/jpeg",
                        new byte[]{1}
                );

        when(
                risks.calculateRisk(
                        any(RequestType.class),
                        any(ResolvedForm.class)
                )
        ).thenReturn(
                new RiskAssessment(62, Risk.HIGH),
                new RiskAssessment(100, Risk.CRITICAL)
        );

        assertNotNull(
                service.create(
                        request(),
                        identity(),
                        new MockMultipartFile[]{evidence}
                ).ticketId()
        );

        assertNotNull(
                service.create(
                        request(),
                        identity(),
                        new MockMultipartFile[]{evidence}
                ).ticketId()
        );

        verify(
                attachments,
                times(2)
        ).storeForTicket(
                any(),
                any(),
                argThat(items -> items.size() == 1),
                any()
        );
    }

    @Test
    void highAndCriticalRiskWithoutEvidenceDoNotPersist() {
        when(
                risks.calculateRisk(
                        any(RequestType.class),
                        any(ResolvedForm.class)
                )
        ).thenReturn(
                new RiskAssessment(62, Risk.HIGH),
                new RiskAssessment(100, Risk.CRITICAL)
        );

        assertThrows(
                EvidenceRequiredException.class,
                () -> service.create(
                        request(),
                        identity(),
                        null
                )
        );

        assertThrows(
                EvidenceRequiredException.class,
                () -> service.create(
                        request(),
                        identity(),
                        null
                )
        );

        verify(tickets, never()).save(any());
        verify(activities, never()).save(any());
        verify(locations, never()).save(any());
    }

    @Test
    void missingInvalidAndInactiveRequestTypesDoNotPersist() {
        when(requestTypes.findById(99L))
                .thenReturn(Optional.empty());

        assertThrows(
                ResourceNotFoundException.class,
                () -> service.create(
                        new CreateTicketRequest(
                                99L,
                                "s",
                                "d",
                                Map.of(),
                                null
                        ),
                        identity(),
                        null
                )
        );

        requestType.setActive(false);

        assertThrows(
                InvalidTicketRequestException.class,
                () -> service.create(
                        request(),
                        identity(),
                        null
                )
        );

        verify(tickets, never()).save(any());
    }

    @Test
    void validationFailureDoesNotPersist() {
        when(forms.resolveAndValidate(any(), any()))
                .thenThrow(new FormValidationException("invalid"));

        assertThrows(
                FormValidationException.class,
                () -> service.create(
                        request(),
                        identity(),
                        null
                )
        );

        verify(tickets, never()).save(any());
    }

    @Test
    void trackingCodesAreDifferentAndOnlyTheirHashesAreStored() {
        when(
                risks.calculateRisk(
                        any(RequestType.class),
                        any(ResolvedForm.class)
                )
        ).thenReturn(new RiskAssessment(0, Risk.LOW));

        CreateTicketResponse first =
                service.create(
                        request(),
                        identity(),
                        null
                );

        CreateTicketResponse second =
                service.create(
                        request(),
                        identity(),
                        null
                );

        assertNotEquals(
                first.trackingCode(),
                second.trackingCode()
        );

        verify(
                tickets,
                times(2)
        ).save(
                argThat(ticket ->
                        !ticket.getTrackingCodeHash()
                                .equals(first.trackingCode())
                                && !ticket.getTrackingCodeHash()
                                .equals(second.trackingCode())
                )
        );
    }

    @Test
    void createdTrackingCodeImmediatelyFindsTheSameTicket() {
        allowLowRisk();

        CreateTicketResponse created =
                service.create(
                        request(),
                        identity(),
                        null
                );

        var savedTicket =
                org.mockito.ArgumentCaptor.forClass(Ticket.class);

        verify(tickets).save(savedTicket.capture());

        Ticket ticket = savedTicket.getValue();

        ticket.setCreatedAt(
                Instant.parse("2026-09-02T18:00:00Z")
        );

        when(
                tickets.findByTrackingCodeHash(
                        trackingCodes.hash(created.trackingCode())
                )
        ).thenReturn(Optional.of(ticket));

        var tracked =
                new TrackingService(
                        tickets,
                        trackingCodes
                ).findByTrackingCode(
                        created.trackingCode()
                );

        assertEquals(
                created.publicId(),
                tracked.getPublicId()
        );

        assertEquals(
                created.status(),
                tracked.getStatus()
        );
    }

    @Test
    void minimumPriorityIsAlwaysAppliedAsFloor() {
        requestType.setMinimumPriority(Priority.HIGH);

        when(
                risks.calculateRisk(
                        any(RequestType.class),
                        any(ResolvedForm.class)
                )
        ).thenReturn(new RiskAssessment(0, Risk.LOW));

        service.create(
                request(),
                identity(),
                null
        );

        verify(tickets).save(
                argThat(ticket ->
                        ticket.getCurrentPriority()
                                == Priority.HIGH
                )
        );
    }

    @Test
    void requiredLocationRejectsNullAndEmptyObjects() {
        requestType.setRequiresLocation(true);
        allowLowRisk();

        assertThrows(
                InvalidTicketRequestException.class,
                () -> service.create(
                        request(),
                        identity(),
                        null
                )
        );

        assertThrows(
                InvalidTicketRequestException.class,
                () -> service.create(
                        request(
                                location(
                                        null,
                                        null,
                                        null,
                                        null
                                )
                        ),
                        identity(),
                        null
                )
        );

        verify(tickets, never()).save(any());
    }

    @Test
    void locationRejectsLatitudeOrLongitudeWhenProvidedAlone() {
        allowLowRisk();

        InvalidTicketRequestException latitudeError =
                assertThrows(
                        InvalidTicketRequestException.class,
                        () -> service.create(
                                request(
                                        location(
                                                null,
                                                BigDecimal.ZERO,
                                                null,
                                                null
                                        )
                                ),
                                identity(),
                                null
                        )
                );

        InvalidTicketRequestException longitudeError =
                assertThrows(
                        InvalidTicketRequestException.class,
                        () -> service.create(
                                request(
                                        location(
                                                null,
                                                null,
                                                BigDecimal.ZERO,
                                                null
                                        )
                                ),
                                identity(),
                                null
                        )
                );

        assertEquals(
                "La latitud y longitud deben informarse juntas",
                latitudeError.getMessage()
        );

        assertEquals(
                "La latitud y longitud deben informarse juntas",
                longitudeError.getMessage()
        );

        verify(tickets, never()).save(any());
    }

    @Test
    void locationRejectsCoordinatesOutsideTheirRanges() {
        allowLowRisk();

        assertThrows(
                InvalidTicketRequestException.class,
                () -> service.create(
                        request(
                                location(
                                        null,
                                        new BigDecimal("90.1"),
                                        BigDecimal.ZERO,
                                        null
                                )
                        ),
                        identity(),
                        null
                )
        );

        assertThrows(
                InvalidTicketRequestException.class,
                () -> service.create(
                        request(
                                location(
                                        null,
                                        BigDecimal.ZERO,
                                        new BigDecimal("180.1"),
                                        null
                                )
                        ),
                        identity(),
                        null
                )
        );

        verify(tickets, never()).save(any());
    }

    @Test
    void locationRejectsUnknownNeighborhood() {
        allowLowRisk();

        UUID neighborhoodId = UUID.randomUUID();

        when(neighborhoods.existsById(neighborhoodId))
                .thenReturn(false);

        ResourceNotFoundException exception =
                assertThrows(
                        ResourceNotFoundException.class,
                        () -> service.create(
                                request(
                                        location(
                                                null,
                                                null,
                                                null,
                                                neighborhoodId
                                        )
                                ),
                                identity(),
                                null
                        )
                );

        assertEquals(
                "Barrio no encontrado",
                exception.getMessage()
        );

        verify(tickets, never()).save(any());
    }

    @Test
    void validRequiredLocationAllowsCreation() {
        requestType.setRequiresLocation(true);
        allowLowRisk();

        assertNotNull(
                service.create(
                        request(
                                location(
                                        "Av. Siempre Viva 742",
                                        null,
                                        null,
                                        null
                                )
                        ),
                        identity(),
                        null
                ).ticketId()
        );

        verify(locations).save(
                any(TicketLocation.class)
        );
    }

    @Test
    void optionalNullLocationAllowsCreationAndUsesOneControlledCreationInstant() {
        allowLowRisk();

        assertNotNull(
                service.create(
                        request(),
                        identity(),
                        null
                ).ticketId()
        );

        verify(tickets).save(
                argThat(ticket ->
                        ticket.getCreatedAt()
                                .equals(clock.instant())
                                && ticket.getUpdatedAt() == null
                                && ticket.getStatusChangedAt() != null
                )
        );

        verify(locations, never()).save(any());

        verify(
                attachments,
                never()
        ).storeForTicket(
                any(),
                any(),
                anyList(),
                any()
        );
    }

    @Test
    void creationStoresCalculatedResolutionDueAt() {
        allowLowRisk();

        Instant dueAt =
                Instant.parse("2026-09-04T18:00:00Z");

        when(
                sla.calculateResolutionDueAt(
                        clock.instant(),
                        Priority.LOW,
                        TicketType.REQUEST
                )
        ).thenReturn(Optional.of(dueAt));

        service.create(
                request(),
                identity(),
                null
        );

        verify(tickets).save(
                argThat(ticket ->
                        dueAt.equals(
                                ticket.getResolutionDueAt()
                        )
                )
        );
    }

    @Test
    void creationStoresFirstResponseDueAtFromCreatedAt() {
        allowLowRisk();

        Instant dueAt =
                Instant.parse("2026-09-04T14:00:00Z");

        when(
                sla.calculateDueAt(
                        clock.instant(),
                        Priority.LOW,
                        SlaType.FIRST_RESPONSE
                )
        ).thenReturn(Optional.of(dueAt));

        service.create(
                request(),
                identity(),
                null
        );

        verify(tickets).save(
                argThat(ticket ->
                        dueAt.equals(
                                ticket.getFirstResponseDueAt()
                        )
                )
        );
    }

    @Test
    void creationWithoutPolicyStoresNullResolutionDueAt() {
        allowLowRisk();

        service.create(
                request(),
                identity(),
                null
        );

        verify(tickets).save(
                argThat(ticket ->
                        ticket.getResolutionDueAt() == null
                )
        );
    }

    @Test
    void criticalCreationUsesTheCriticalPolicyResult() {
        MockMultipartFile evidence =
                new MockMultipartFile(
                        "evidence",
                        "photo.jpg",
                        "image/jpeg",
                        new byte[]{1}
                );

        Instant dueAt =
                Instant.parse("2026-09-04T16:00:00Z");

        when(
                risks.calculateRisk(
                        any(RequestType.class),
                        any(ResolvedForm.class)
                )
        ).thenReturn(
                new RiskAssessment(
                        0,
                        Risk.CRITICAL
                )
        );

        when(
                sla.calculateResolutionDueAt(
                        clock.instant(),
                        Priority.CRITICAL,
                        TicketType.REQUEST
                )
        ).thenReturn(Optional.of(dueAt));

        service.create(
                request(),
                identity(),
                new MockMultipartFile[]{evidence}
        );

        verify(tickets).save(
                argThat(ticket ->
                        ticket.getCurrentPriority()
                                == Priority.CRITICAL
                                && dueAt.equals(
                                ticket.getResolutionDueAt()
                        )
                )
        );
    }

    @Test
    void routingRecalculatesWhenPolicyRequiresIt() {
        UUID id = UUID.randomUUID();

        Ticket ticket = new Ticket();
        ticket.setCurrentPriority(Priority.CRITICAL);
        ticket.setTicketType(TicketType.REQUEST);
        ticket.setResolutionDueAt(
                Instant.parse("2026-09-04T13:00:00Z")
        );

        Instant routedAt =
                Instant.parse("2026-09-05T12:00:00Z");

        Instant dueAt =
                Instant.parse("2026-09-05T14:00:00Z");

        ticket.setCreatedAt(clock.instant());

        when(tickets.findByIdForUpdate(id))
                .thenReturn(Optional.of(ticket));

        when(
                sla.calculateResolutionDueAt(
                        clock.instant(),
                        Priority.CRITICAL,
                        TicketType.REQUEST
                )
        ).thenReturn(Optional.of(dueAt));

        when(tickets.save(ticket))
                .thenReturn(ticket);

        Ticket routed =
                service.route(
                        id,
                        "AREA-2",
                        routedAt
                );

        assertEquals(
                dueAt,
                routed.getResolutionDueAt()
        );

        assertEquals(
                TicketStatus.ROUTED,
                routed.getCurrentStatus()
        );
    }

    @Test
    void routingRecalculatesFromCreatedAtAndPreservesTheSameResult() {
        UUID id = UUID.randomUUID();

        Instant originalDueAt =
                Instant.parse("2026-09-06T12:00:00Z");

        Ticket ticket =
                routedTicket(
                        Priority.HIGH,
                        originalDueAt
                );

        ticket.setCreatedAt(clock.instant());

        when(tickets.findByIdForUpdate(id))
                .thenReturn(Optional.of(ticket));

        when(
                sla.calculateResolutionDueAt(
                        clock.instant(),
                        Priority.HIGH,
                        TicketType.REQUEST
                )
        ).thenReturn(Optional.of(originalDueAt));

        when(tickets.save(ticket))
                .thenReturn(ticket);

        assertEquals(
                originalDueAt,
                service.route(
                        id,
                        "AREA-2",
                        clock.instant()
                ).getResolutionDueAt()
        );

        verify(sla).calculateResolutionDueAt(
                clock.instant(),
                Priority.HIGH,
                TicketType.REQUEST
        );
    }

    @Test
    void routingSetsMissingDueAtFromCreatedAt() {
        UUID id = UUID.randomUUID();

        Ticket ticket =
                routedTicket(
                        Priority.MEDIUM,
                        null
                );

        Instant dueAt =
                Instant.parse("2026-09-07T12:00:00Z");

        ticket.setCreatedAt(clock.instant());

        when(tickets.findByIdForUpdate(id))
                .thenReturn(Optional.of(ticket));

        when(
                sla.calculateResolutionDueAt(
                        clock.instant(),
                        Priority.MEDIUM,
                        TicketType.REQUEST
                )
        ).thenReturn(Optional.of(dueAt));

        when(tickets.save(ticket))
                .thenReturn(ticket);

        assertEquals(
                dueAt,
                service.route(
                        id,
                        "AREA-2",
                        clock.instant()
                ).getResolutionDueAt()
        );
    }

    @Test
    void routingAfterPriorityChangeStillUsesOriginalCreatedAt() {
        UUID id = UUID.randomUUID();

        Ticket ticket =
                routedTicket(
                        Priority.HIGH,
                        Instant.parse("2026-09-10T12:00:00Z")
                );

        ticket.setCreatedAt(
                Instant.parse("2026-09-01T12:00:00Z")
        );

        Instant recalculated =
                Instant.parse("2026-09-04T12:00:00Z");

        when(tickets.findByIdForUpdate(id))
                .thenReturn(Optional.of(ticket));

        when(
                sla.calculateResolutionDueAt(
                        ticket.getCreatedAt(),
                        Priority.HIGH,
                        TicketType.REQUEST
                )
        ).thenReturn(Optional.of(recalculated));

        when(tickets.save(ticket))
                .thenReturn(ticket);

        assertEquals(
                recalculated,
                service.route(
                        id,
                        "AREA-2",
                        clock.instant()
                ).getResolutionDueAt()
        );

        verify(sla).calculateResolutionDueAt(
                ticket.getCreatedAt(),
                Priority.HIGH,
                TicketType.REQUEST
        );
    }

    @Test
    void routingPreservesFirstResponseDueAt() {
        UUID id = UUID.randomUUID();
        Instant firstResponseDueAt = Instant.parse("2026-09-02T12:00:00Z");
        Ticket ticket = routedTicket(Priority.MEDIUM, Instant.parse("2026-09-05T12:00:00Z"));
        ticket.setCreatedAt(Instant.parse("2026-09-01T12:00:00Z"));
        ticket.setFirstResponseDueAt(firstResponseDueAt);

        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(ticket));
        when(sla.calculateResolutionDueAt(
                ticket.getCreatedAt(), Priority.MEDIUM, TicketType.REQUEST
        )).thenReturn(Optional.of(ticket.getResolutionDueAt()));
        when(tickets.save(ticket)).thenReturn(ticket);

        Ticket routed = service.route(id, "AREA-2", clock.instant());

        assertEquals(firstResponseDueAt, routed.getFirstResponseDueAt());
        verify(sla, never()).calculateDueAt(any(), any(Priority.class), eq(SlaType.FIRST_RESPONSE));
    }

    @Test
    void duplicateCannotBeRoutedOrReceiveAnIndependentSla() {
        UUID id = UUID.randomUUID();
        Ticket duplicate = routedTicket(Priority.HIGH, null);
        duplicate.setCurrentStatus(TicketStatus.DUPLICATE);
        duplicate.setCreatedAt(Instant.parse("2026-09-01T12:00:00Z"));

        when(tickets.findByIdForUpdate(id)).thenReturn(Optional.of(duplicate));

        assertThrows(
                InvalidTicketRequestException.class,
                () -> service.route(id, "AREA-2", clock.instant())
        );

        verifyNoInteractions(sla);
        verify(tickets, never()).save(any());
    }

    private Ticket routedTicket(
            Priority priority,
            Instant dueAt
    ) {
        Ticket ticket = new Ticket();

        ticket.setCurrentPriority(priority);
        ticket.setTicketType(TicketType.REQUEST);
        ticket.setResolutionDueAt(dueAt);

        return ticket;
    }

    @Test
    void voluntaryEvidenceIsStoredForLowRisk() {
        allowLowRisk();

        MockMultipartFile evidence =
                new MockMultipartFile(
                        "evidence",
                        "photo.webp",
                        "image/webp",
                        new byte[]{1}
                );

        service.create(
                request(),
                identity(),
                new MockMultipartFile[]{evidence}
        );

        verify(attachments).storeForTicket(
                any(Ticket.class),
                any(AuthenticatedIdentity.class),
                argThat(items ->
                        items.size() == 1
                                && items.getFirst().file() == evidence
                ),
                any()
        );
    }

    @Test
    void storageFailurePreventsActivityAndSuccessfulResponse() {
        allowLowRisk();

        MockMultipartFile evidence =
                new MockMultipartFile(
                        "evidence",
                        "photo.jpg",
                        "image/jpeg",
                        new byte[]{1}
                );

        doThrow(
                new AttachmentStorageUnavailableException()
        ).when(attachments)
                .storeForTicket(
                        any(),
                        any(),
                        anyList(),
                        any()
                );

        assertThrows(
                AttachmentStorageUnavailableException.class,
                () -> service.create(
                        request(),
                        identity(),
                        new MockMultipartFile[]{evidence}
                )
        );

        verify(activities, never())
                .save(any());
    }

    @Test
    void persistsFalseZeroAndExactTemplateId() {
        allowLowRisk();

        Map<String, Object> formData =
                new HashMap<>();

        formData.put("danger", false);
        formData.put("amount", 0);

        service.create(
                new CreateTicketRequest(
                        1L,
                        "Resumen",
                        "Descripción",
                        formData,
                        null
                ),
                identity(),
                null
        );

        verify(tickets).save(
                argThat(ticket ->
                        ticket.getFormTemplateId().equals(3L)
                                && Boolean.FALSE.equals(
                                ticket.getFormData().get("danger")
                        )
                                && Integer.valueOf(0).equals(
                                ticket.getFormData().get("amount")
                        )
                )
        );
    }

    @Test
    void requestTypeWithoutTemplatePersistsNullTemplateForEmptyForm() {
        when(forms.resolveAndValidate(any(), any()))
                .thenReturn(
                        new ResolvedForm(
                                null,
                                List.of(),
                                Map.of()
                        )
                );

        allowLowRisk();

        service.create(
                new CreateTicketRequest(
                        1L,
                        "Resumen",
                        "Descripción",
                        Map.of(),
                        null
                ),
                identity(),
                null
        );

        verify(tickets).save(
                argThat(ticket ->
                        ticket.getFormTemplateId() == null
                                && ticket.getFormData().isEmpty()
                )
        );
    }

    private CreateTicketRequest request() {
        return new CreateTicketRequest(
                1L,
                "Resumen",
                "Descripción",
                Map.of("answer", true),
                null
        );
    }

    private CreateTicketRequest request(
            CreateTicketRequest.LocationData location
    ) {
        return new CreateTicketRequest(
                1L,
                "Resumen",
                "Descripción",
                Map.of("answer", true),
                location
        );
    }

    private CreateTicketRequest.LocationData location(
            String addressLine,
            BigDecimal latitude,
            BigDecimal longitude,
            UUID neighborhoodId
    ) {
        return new CreateTicketRequest.LocationData(
                addressLine,
                null,
                null,
                neighborhoodId,
                latitude,
                longitude,
                null
        );
    }

    private void allowLowRisk() {
        when(
                risks.calculateRisk(
                        any(),
                        any(ResolvedForm.class)
                )
        ).thenReturn(
                new RiskAssessment(
                        0,
                        Risk.LOW
                )
        );
    }

    private AuthenticatedIdentity identity() {
        return new AuthenticatedIdentity(
                "citizen",
                UUID.randomUUID(),
                "Citizen",
                null,
                ModuleRole.CITIZEN
        );
    }

    private RequestType requestType(
            boolean active
    ) {
        Category category = new Category();

        Subcategory subcategory =
                new Subcategory();

        subcategory.setCategory(category);

        RequestType type =
                new RequestType();

        type.setId(1L);
        type.setSubcategory(subcategory);
        type.setTicketType(TicketType.REQUEST);
        type.setResponsibleAreaId("M6");
        type.setMinimumPriority(Priority.LOW);
        type.setBaseRisk(Risk.LOW);
        type.setAffectedPopulationFactor(BigDecimal.ZERO);
        type.setRequiresLocation(false);
        type.setActive(active);

        return type;
    }
}