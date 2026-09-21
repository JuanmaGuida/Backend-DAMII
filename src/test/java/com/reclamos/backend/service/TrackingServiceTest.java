package com.reclamos.backend.service;

import com.reclamos.backend.dto.response.TicketAttachmentResponse;
import com.reclamos.backend.dto.response.TrackingTicketResponse;
import com.reclamos.backend.dto.response.PendingInformationRequestResponse;
import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.InformationRequestStatus;
import com.reclamos.backend.entity.Category;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.RequestType;
import com.reclamos.backend.entity.Subcategory;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.entity.TicketStatus;
import com.reclamos.backend.exception.TrackingTicketNotFoundException;
import com.reclamos.backend.repository.AttachmentRepository;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class TrackingServiceTest {
    private static final String CODE = "0123456789abcdefghijklmnopqrstuv";
    private static final String HASH = "secure-hash";
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final TrackingCodeService trackingCodes = mock(TrackingCodeService.class);
    private final TicketSlaService ticketSlas = mock(TicketSlaService.class);
    private final InformationRequestProjectionService projections = mock(InformationRequestProjectionService.class);
    private final AttachmentRepository attachmentRepository = mock(AttachmentRepository.class);
    private final AttachmentReferenceService attachmentReferenceService = mock(AttachmentReferenceService.class);
    private final TrackingService service = new TrackingService(
            tickets, trackingCodes, ticketSlas, projections, attachmentRepository, attachmentReferenceService);

    @BeforeEach
    void setUp() {
        when(ticketSlas.findDeadlineSnapshot(any()))
                .thenReturn(new TicketSlaService.DeadlineSnapshot(null, null));
        when(attachmentRepository.findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(any(), any()))
                .thenReturn(List.of());
    }

    @Test
    void validCodeReturnsOnlyWhitelistedCurrentPublicTicketData() {
        Ticket ticket = ticket();
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(ticket));

        TrackingTicketResponse response = service.findByTrackingCode(CODE);

        assertEquals("TK-2026-000123", response.getPublicId());
        assertEquals(TicketStatus.IN_PROGRESS, response.getStatus());
        assertEquals("Resumen público", response.getSummary());
        assertEquals("Descripción completa del reclamo", response.getDescription());
        assertEquals(Instant.parse("2026-09-01T10:00:00Z"), response.getCreatedAt());
        assertEquals(Instant.parse("2026-09-02T10:00:00Z"), response.getStatusChangedAt());
        assertEquals("Tipo de solicitud", response.getRequestType().getName());
        assertEquals("Categoría", response.getCategory().getName());
        assertEquals("Subcategoría", response.getSubcategory().getName());
        assertNull(response.getSla().getFirstResponseDueAt());
        assertNull(response.getSla().getResolutionDueAt());
        verify(trackingCodes).hash(CODE);
        verify(tickets).findByTrackingCodeHash(HASH);
        verify(tickets, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void trackingIncludesSafePendingInformationProjection() {
        Ticket ticket = ticket();
        var pending = new PendingInformationRequestResponse(InformationRequestStatus.PENDING,
                "Adjunte una foto", Instant.parse("2026-09-20T10:00:00Z"),
                Instant.parse("2026-09-21T10:00:00Z"), java.util.List.of());
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(ticket));
        when(projections.findPending(ticket.getId()))
                .thenReturn(new InformationRequestProjectionService.Projection(pending, null));

        assertEquals(pending, service.findByTrackingCode(CODE).getPendingInformationRequest());
    }

    @Test
    void trackingIncludesOnlyPublicAttachmentsWithTheirDownloadReference() {
        Ticket ticket = ticket();
        Attachment attachment = new Attachment();
        attachment.setId(9L);
        attachment.setFileName("foto.jpg");
        attachment.setContentType("image/jpeg");
        attachment.setSizeBytes(1024L);
        attachment.setVisibility(MessageVisibility.PUBLIC);
        attachment.setCreatedAt(Instant.parse("2026-09-05T10:00:00Z"));
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(ticket));
        when(attachmentRepository.findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(
                ticket.getId(), MessageVisibility.PUBLIC)).thenReturn(List.of(attachment));
        when(attachmentReferenceService.downloadUrl(attachment))
                .thenReturn("http://localhost:8080/api/attachments/9/content");

        List<TicketAttachmentResponse> attachments = service.findByTrackingCode(CODE).getAttachments();

        TicketAttachmentResponse expected = new TicketAttachmentResponse(9L, "foto.jpg", "image/jpeg", 1024L,
                MessageVisibility.PUBLIC, Instant.parse("2026-09-05T10:00:00Z"),
                "http://localhost:8080/api/attachments/9/content");
        assertEquals(List.of(expected), attachments);
        // El propietario anónimo nunca queda autenticado por Bearer, así que este
        // caso sólo prueba que TrackingService pide PUBLIC — nunca todos los del
        // ticket — al repositorio; que ese endpoint responda 401 sin token es
        // responsabilidad de AttachmentAccessService/AttachmentController.
        verify(attachmentRepository, never()).findAllByTicket_IdOrderByCreatedAtAsc(any());
    }

    @Test
    void attachmentsAreAnEmptyListRatherThanNullWhenTheTicketHasNonePublic() {
        Ticket ticket = ticket();
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(ticket));
        when(attachmentRepository.findAllByTicket_IdAndVisibilityOrderByCreatedAtAsc(
                ticket.getId(), MessageVisibility.PUBLIC)).thenReturn(List.of());

        assertEquals(List.of(), service.findByTrackingCode(CODE).getAttachments());
    }

    @Test
    void unknownCodeRaisesUniformControlledNotFoundError() {
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);

        TrackingTicketNotFoundException exception = assertThrows(TrackingTicketNotFoundException.class,
                () -> service.findByTrackingCode(CODE));

        assertEquals(TrackingTicketNotFoundException.MESSAGE, exception.getMessage());
    }

    @Test
    void malformedCodeUsesTheSameControlledNotFoundWithoutHashingOrQuerying() {
        when(trackingCodes.isValid("invalid")).thenReturn(false);

        assertThrows(TrackingTicketNotFoundException.class, () -> service.findByTrackingCode("invalid"));

        verify(trackingCodes, never()).hash("invalid");
        verifyNoInteractions(tickets);
    }

    @Test
    void publicDtoContainsNoReservedPropertiesIncludingNestedInternalIds() {
        Set<String> forbidden = Set.of("ticketId", "citizenId", "subjectId", "trackingCode", "trackingCodeHash",
                "trackingAccessCode", "anonymousAccessPassword", "anonymousAccessPasswordHash",
                "anonymousContact", "anonymousContactChannel", "anonymousContactValue",
                "riskScore", "riskLevel", "internalMessage", "actorId", "resolvedById", "sourceModuleId",
                "responsibleAreaId", "firstResponseDueAt", "resolutionDueAt", "policyId", "cycleNumber");
        Set<String> fieldNames = Arrays.stream(TrackingTicketResponse.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet());
        assertTrueNoIntersection(fieldNames, forbidden);
        assertFalse(Arrays.stream(TrackingTicketResponse.RequestTypeSummary.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("id")));
        assertFalse(Arrays.stream(TrackingTicketResponse.RequestTypeSummary.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("code")));
        assertFalse(Arrays.stream(TrackingTicketResponse.CategorySummary.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("id")));
        assertFalse(Arrays.stream(TrackingTicketResponse.SubcategorySummary.class.getDeclaredFields())
                .anyMatch(field -> field.getName().equals("id")));
        Set<String> slaFields = Arrays.stream(TrackingTicketResponse.SlaSummary.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("firstResponseDueAt", "resolutionDueAt"),
                slaFields);
    }

    @Test
    void returnsBothDeadlinesDerivedFromTicketSla() {
        Ticket ticket = ticket();
        Instant firstResponse = Instant.parse("2026-09-02T18:00:00Z");
        Instant resolution = Instant.parse("2026-09-18T18:00:00Z");
        when(ticketSlas.findDeadlineSnapshot(ticket))
                .thenReturn(new TicketSlaService.DeadlineSnapshot(firstResponse, resolution));
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(ticket));

        TrackingTicketResponse.SlaSummary result = service.findByTrackingCode(CODE).getSla();

        assertEquals(firstResponse, result.getFirstResponseDueAt());
        assertEquals(resolution, result.getResolutionDueAt());
    }

    @Test
    void toleratesOneMissingDeadline() {
        Ticket ticket = ticket();
        Instant resolution = Instant.parse("2026-09-18T18:00:00Z");
        when(ticketSlas.findDeadlineSnapshot(ticket))
                .thenReturn(new TicketSlaService.DeadlineSnapshot(null, resolution));
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(ticket));

        TrackingTicketResponse.SlaSummary result = service.findByTrackingCode(CODE).getSla();

        assertNull(result.getFirstResponseDueAt());
        assertEquals(resolution, result.getResolutionDueAt());
    }

    @Test
    void duplicateReadsDeadlinesThroughTicketSlaProjection() {
        Ticket main = ticket();
        Instant firstResponse = Instant.parse("2026-09-02T18:00:00Z");
        Instant resolution = Instant.parse("2026-09-18T18:00:00Z");
        Ticket duplicate = ticket();
        duplicate.setId(UUID.fromString("20000000-0000-0000-0000-000000000002"));
        duplicate.setCurrentStatus(TicketStatus.DUPLICATE);
        duplicate.setMainTicket(main);
        when(ticketSlas.findDeadlineSnapshot(duplicate))
                .thenReturn(new TicketSlaService.DeadlineSnapshot(firstResponse, resolution));
        when(trackingCodes.isValid(CODE)).thenReturn(true);
        when(trackingCodes.hash(CODE)).thenReturn(HASH);
        when(tickets.findByTrackingCodeHash(HASH)).thenReturn(Optional.of(duplicate));

        TrackingTicketResponse response = service.findByTrackingCode(CODE);

        assertEquals(firstResponse, response.getSla().getFirstResponseDueAt());
        assertEquals(resolution, response.getSla().getResolutionDueAt());
        verify(ticketSlas).findDeadlineSnapshot(duplicate);
        verify(tickets, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void lookupRemainsTransactionallyReadOnly() throws NoSuchMethodException {
        Transactional transactional = TrackingService.class
                .getMethod("findByTrackingCode", String.class)
                .getAnnotation(Transactional.class);

        assertTrue(transactional.readOnly());
    }

    private void assertTrueNoIntersection(Set<String> actual, Set<String> forbidden) {
        assertEquals(Set.of(), actual.stream().filter(forbidden::contains)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private Ticket ticket() {
        Category category = new Category();
        category.setId(2L);
        category.setName("Categoría");
        Subcategory subcategory = new Subcategory();
        subcategory.setId(5L);
        subcategory.setName("Subcategoría");
        subcategory.setCategory(category);
        RequestType requestType = new RequestType();
        requestType.setId(15L);
        requestType.setCode("RT-1");
        requestType.setName("Tipo de solicitud");
        requestType.setSubcategory(subcategory);
        Ticket ticket = new Ticket();
        ticket.setId(UUID.fromString("20000000-0000-0000-0000-000000000001"));
        ticket.setPublicId("TK-2026-000123");
        ticket.setTrackingCodeHash("never-public");
        ticket.setCitizenId(UUID.randomUUID());
        ticket.setResponsibleAreaId("internal-area");
        ticket.setRequestType(requestType);
        ticket.setSummary("Resumen público");
        ticket.setDescription("Descripción completa del reclamo");
        ticket.setCurrentStatus(TicketStatus.IN_PROGRESS);
        ticket.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));
        ticket.setStatusChangedAt(Instant.parse("2026-09-02T10:00:00Z"));
        return ticket;
    }
}
