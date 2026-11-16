package com.reclamos.backend.service;

import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.ResourceNotFoundException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.AttachmentRepository;
import com.reclamos.backend.storage.FileStorage;
import com.reclamos.backend.storage.StoredContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AttachmentAccessServiceTest {
    private final AttachmentRepository repository = mock(AttachmentRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private final AttachmentAccessService service = new AttachmentAccessService(repository, storage);
    private final UUID ownerId = UUID.randomUUID();
    private final UUID ticketId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        reset(repository, storage);
        when(storage.read(anyString())).thenReturn(new StoredContent(new ByteArrayResource(new byte[]{1, 2, 3}), 3));
    }

    @Test
    void ownerCanDownloadPublicButNotInternalAndForeignCitizenCannotDownload() {
        stub(attachment(MessageVisibility.PUBLIC, "M2"));
        assertEquals(3, service.download(1L, identity(ModuleRole.CITIZEN, ownerId, null)).sizeBytes());
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.download(1L, identity(ModuleRole.CITIZEN, UUID.randomUUID(), null)));

        stub(attachment(MessageVisibility.INTERNAL, "M2"));
        assertThrows(UnauthorizedTicketOperationException.class,
                () -> service.download(1L, identity(ModuleRole.CITIZEN, ownerId, null)));
    }

    @Test
    void agentAdminAndAreaWithinScopeCanDownloadInternalButDifferentAreaCannot() {
        stub(attachment(MessageVisibility.INTERNAL, "AREA-1"));
        assertDoesNotThrow(() -> service.download(1L, identity(ModuleRole.AGENT, UUID.randomUUID(), null)));
        assertDoesNotThrow(() -> service.download(1L, identity(ModuleRole.ADMIN, UUID.randomUUID(), null)));
        assertDoesNotThrow(() -> service.download(1L,
                identity(ModuleRole.AREA_RESPONSIBLE, UUID.randomUUID(), "AREA-1")));
        assertThrows(UnauthorizedTicketOperationException.class, () -> service.download(1L,
                identity(ModuleRole.AREA_RESPONSIBLE, UUID.randomUUID(), "AREA-2")));
    }

    @Test
    void nonexistentAttachmentIsNotFoundAndStorageIsNeverConsulted() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.download(99L, identity(ModuleRole.ADMIN, UUID.randomUUID(), null)));
        verifyNoInteractions(storage);
    }

    @Test
    void authenticatedAnonymousOwnerGetsOnlyPublicAttachmentFromSameTicket() {
        stub(attachment(MessageVisibility.PUBLIC, "M2"));
        assertDoesNotThrow(() -> service.downloadAnonymous(1L, ticketId));
        assertThrows(ResourceNotFoundException.class,
                () -> service.downloadAnonymous(1L, UUID.randomUUID()));

        stub(attachment(MessageVisibility.INTERNAL, "M2"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.downloadAnonymous(1L, ticketId));
    }

    private void stub(Attachment attachment) {
        when(repository.findById(1L)).thenReturn(Optional.of(attachment));
    }

    private Attachment attachment(MessageVisibility visibility, String areaId) {
        Ticket ticket = new Ticket();
        ticket.setId(ticketId);
        ticket.setCitizenId(ownerId);
        ticket.setAnonymous(false);
        ticket.setResponsibleAreaId(areaId);
        Attachment attachment = new Attachment();
        attachment.setId(1L);
        attachment.setTicket(ticket);
        attachment.setFileName("proof.pdf");
        attachment.setContentType("application/pdf");
        attachment.setSizeBytes(3);
        attachment.setStorageKey("tickets/one/key");
        attachment.setVisibility(visibility);
        return attachment;
    }

    private AuthenticatedIdentity identity(ModuleRole role, UUID citizenId, String areaId) {
        return new AuthenticatedIdentity("subject", citizenId, "User", areaId, role);
    }
}
