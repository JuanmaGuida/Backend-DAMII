package com.reclamos.backend.service;

import com.reclamos.backend.dto.request.AttachmentUploadRequest;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.InvalidAttachmentException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.TicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.web.MockMultipartFile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TicketAttachmentUploadServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T12:00:00Z");
    private static final UUID TICKET_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final String AREA_ID = "AREA-1";

    @Mock private TicketRepository ticketRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private AttachmentReferenceService attachmentReferenceService;

    private TicketAttachmentUploadService service;
    private Ticket ticket;
    private MockMultipartFile file;
    private AttachmentService.ValidatedAttachment validated;

    @BeforeEach
    void setUp() {
        service = new TicketAttachmentUploadService(ticketRepository, attachmentService,
                attachmentReferenceService, Clock.fixed(NOW, ZoneOffset.UTC));
        ticket = ticket(false, OWNER_ID, AREA_ID);
        file = new MockMultipartFile("attachments", "proof.pdf", "application/pdf", new byte[]{1});
        validated = new AttachmentService.ValidatedAttachment(file, "proof.pdf", "application/pdf", 1);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
    }

    @Test
    void identifiedOwnerUploadsPublicAsCitizenAndReceivesStableMetadata() {
        stubSuccessfulStore();

        var response = service.upload(TICKET_ID, request(MessageVisibility.PUBLIC),
                new MockMultipartFile[]{file}, identity(ModuleRole.CITIZEN, OWNER_ID, null));

        ArgumentCaptor<AttachmentService.UploadActor> actor = ArgumentCaptor.forClass(
                AttachmentService.UploadActor.class);
        verify(attachmentService).storeForTicket(eq(ticket), actor.capture(),
                eq(MessageVisibility.PUBLIC), eq(List.of(validated)), eq(NOW));
        assertThat(actor.getValue()).isEqualTo(new AttachmentService.UploadActor(
                ActorType.CITIZEN, OWNER_ID.toString()));
        assertThat(response).singleElement().satisfies(item -> {
            assertThat(item.getId()).isEqualTo(10L);
            assertThat(item.getVisibility()).isEqualTo(MessageVisibility.PUBLIC);
            assertThat(item.getDownloadUrl()).isEqualTo("https://m2.example/api/attachments/10/content");
        });
    }

    @Test
    void identifiedOwnerCannotUploadInternalAndForeignCitizenCannotUpload() {
        assertThatThrownBy(() -> service.upload(TICKET_ID, request(MessageVisibility.INTERNAL),
                new MockMultipartFile[]{file}, identity(ModuleRole.CITIZEN, OWNER_ID, null)))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(() -> service.upload(TICKET_ID, request(MessageVisibility.PUBLIC),
                new MockMultipartFile[]{file}, identity(ModuleRole.CITIZEN, UUID.randomUUID(), null)))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        verify(attachmentService, never()).validate(any());
    }

    @ParameterizedTest
    @EnumSource(value = ModuleRole.class, names = {"AGENT", "ADMIN"})
    void agentAndAdminUploadPublicOrInternalOnForeignTicketWithEffectiveStaffActor(ModuleRole role) {
        stubSuccessfulStore();
        AuthenticatedIdentity staff = identity(role, UUID.randomUUID(), "OTHER-AREA");

        for (MessageVisibility visibility : MessageVisibility.values()) {
            service.upload(TICKET_ID, request(visibility), new MockMultipartFile[]{file}, staff);
        }

        ArgumentCaptor<AttachmentService.UploadActor> actors = ArgumentCaptor.forClass(
                AttachmentService.UploadActor.class);
        ArgumentCaptor<MessageVisibility> visibilities = ArgumentCaptor.forClass(MessageVisibility.class);
        verify(attachmentService, org.mockito.Mockito.times(2)).storeForTicket(
                eq(ticket), actors.capture(), visibilities.capture(), any(), eq(NOW));
        ActorType expected = role == ModuleRole.AGENT ? ActorType.AGENT : ActorType.ADMIN;
        assertThat(actors.getAllValues()).allSatisfy(actor -> {
            assertThat(actor.type()).isEqualTo(expected);
            assertThat(actor.id()).isEqualTo(staff.citizenId().toString());
        });
        assertThat(visibilities.getAllValues()).containsExactlyInAnyOrder(
                MessageVisibility.PUBLIC, MessageVisibility.INTERNAL);
    }

    @ParameterizedTest
    @EnumSource(value = ModuleRole.class, names = {"AGENT", "ADMIN", "AREA_RESPONSIBLE"})
    void staffOwnerUsesCitizenCapacityAndCannotUploadInternal(ModuleRole role) {
        stubSuccessfulStore();
        AuthenticatedIdentity owner = identity(role, OWNER_ID, AREA_ID);

        service.upload(TICKET_ID, request(MessageVisibility.PUBLIC), new MockMultipartFile[]{file}, owner);
        assertThatThrownBy(() -> service.upload(TICKET_ID, request(MessageVisibility.INTERNAL),
                new MockMultipartFile[]{file}, owner))
                .isInstanceOf(UnauthorizedTicketOperationException.class);

        ArgumentCaptor<AttachmentService.UploadActor> actor = ArgumentCaptor.forClass(
                AttachmentService.UploadActor.class);
        verify(attachmentService).storeForTicket(eq(ticket), actor.capture(),
                eq(MessageVisibility.PUBLIC), any(), eq(NOW));
        assertThat(actor.getValue().type()).isEqualTo(ActorType.CITIZEN);
    }

    @ParameterizedTest
    @EnumSource(MessageVisibility.class)
    void areaResponsibleUploadsBothVisibilitiesOnlyInsideItsArea(MessageVisibility visibility) {
        stubSuccessfulStore();
        service.upload(TICKET_ID, request(visibility), new MockMultipartFile[]{file},
                identity(ModuleRole.AREA_RESPONSIBLE, UUID.randomUUID(), AREA_ID));

        ArgumentCaptor<AttachmentService.UploadActor> actor = ArgumentCaptor.forClass(
                AttachmentService.UploadActor.class);
        verify(attachmentService).storeForTicket(eq(ticket), actor.capture(), eq(visibility), any(), eq(NOW));
        assertThat(actor.getValue().type()).isEqualTo(ActorType.AREA_RESPONSIBLE);
    }

    @Test
    void areaResponsibleOutsideAreaIsForbidden() {
        assertThatThrownBy(() -> service.upload(TICKET_ID, request(MessageVisibility.PUBLIC),
                new MockMultipartFile[]{file},
                identity(ModuleRole.AREA_RESPONSIBLE, UUID.randomUUID(), "AREA-2")))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        verify(attachmentService, never()).validate(any());
    }

    @Test
    void accreditedAnonymousOwnerUploadsOnlyPublicWithNullActorId() {
        ticket = ticket(true, null, AREA_ID);
        when(ticketRepository.findById(TICKET_ID)).thenReturn(Optional.of(ticket));
        stubSuccessfulStore();

        service.uploadAnonymous(TICKET_ID, request(MessageVisibility.PUBLIC),
                new MockMultipartFile[]{file});

        ArgumentCaptor<AttachmentService.UploadActor> actor = ArgumentCaptor.forClass(
                AttachmentService.UploadActor.class);
        verify(attachmentService).storeForTicket(eq(ticket), actor.capture(),
                eq(MessageVisibility.PUBLIC), any(), eq(NOW));
        assertThat(actor.getValue()).isEqualTo(new AttachmentService.UploadActor(ActorType.CITIZEN, null));
        assertThatThrownBy(() -> service.uploadAnonymous(TICKET_ID, request(MessageVisibility.INTERNAL),
                new MockMultipartFile[]{file}))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
    }

    @Test
    void emptyUploadIsRejectedWithoutChangingTicketOrPersistingMetadata() {
        when(attachmentService.validate(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.upload(TICKET_ID, request(MessageVisibility.PUBLIC),
                new MockMultipartFile[0], identity(ModuleRole.CITIZEN, OWNER_ID, null)))
                .isInstanceOf(InvalidAttachmentException.class);

        verify(attachmentService, never()).storeForTicket(any(), any(), any(), any(), any());
        assertThat(ticket.getCitizenId()).isEqualTo(OWNER_ID);
        assertThat(ticket.getResponsibleAreaId()).isEqualTo(AREA_ID);
    }

    private void stubSuccessfulStore() {
        when(attachmentService.validate(any())).thenReturn(List.of(validated));
        when(attachmentService.storeForTicket(eq(ticket), any(), any(), any(), eq(NOW)))
                .thenAnswer(invocation -> {
                    Attachment attachment = new Attachment();
                    attachment.setId(10L);
                    attachment.setTicket(ticket);
                    attachment.setFileName("proof.pdf");
                    attachment.setContentType("application/pdf");
                    attachment.setSizeBytes(1);
                    attachment.setVisibility(invocation.getArgument(2));
                    attachment.setCreatedAt(NOW);
                    return List.of(attachment);
                });
        when(attachmentReferenceService.downloadUrl(any()))
                .thenReturn("https://m2.example/api/attachments/10/content");
    }

    private AttachmentUploadRequest request(MessageVisibility visibility) {
        return new AttachmentUploadRequest(visibility);
    }

    private AuthenticatedIdentity identity(ModuleRole role, UUID citizenId, String areaId) {
        return new AuthenticatedIdentity("subject", citizenId, "User", areaId, role);
    }

    private Ticket ticket(boolean anonymous, UUID citizenId, String areaId) {
        Ticket result = new Ticket();
        result.setId(TICKET_ID);
        result.setAnonymous(anonymous);
        result.setCitizenId(citizenId);
        result.setResponsibleAreaId(areaId);
        return result;
    }
}
