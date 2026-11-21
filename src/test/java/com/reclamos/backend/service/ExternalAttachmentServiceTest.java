package com.reclamos.backend.service;

import com.reclamos.backend.config.AttachmentProperties;
import com.reclamos.backend.dto.UpdateTicketStatusRequest;
import com.reclamos.backend.entity.*;
import com.reclamos.backend.exception.InvalidTicketRequestException;
import com.reclamos.backend.exception.StoredFileNotFoundException;
import com.reclamos.backend.exception.UnauthorizedTicketOperationException;
import com.reclamos.backend.identity.*;
import com.reclamos.backend.repository.*;
import com.reclamos.backend.storage.LocalFileStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalAttachmentServiceTest {
    final ExternalAttachmentReferenceRepository refs=mock(ExternalAttachmentReferenceRepository.class);
    final TicketRepository tickets=mock(TicketRepository.class);
    final ExternalAttachmentDownloader downloader=mock(ExternalAttachmentDownloader.class);
    final AttachmentRepository savedAttachments=mock(AttachmentRepository.class);
    final AttachmentProperties properties=new AttachmentProperties();
    ExternalAttachmentService service;
    LocalFileStorage storage;
    @TempDir Path folder;
    final UUID ticketId=UUID.randomUUID(),owner=UUID.randomUUID();
    final Ticket ticket=new Ticket();
    @BeforeEach void setUp() {
        ticket.setId(ticketId); ticket.setCitizenId(owner); ticket.setResponsibleAreaId("M3");
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
        properties.getStorage().setRoot(folder);
        properties.getLimits().setMaxFiles(5);
        properties.getLimits().setMaxFileSize(DataSize.ofMegabytes(10));
        properties.getLimits().setMaxTotalSize(DataSize.ofMegabytes(50));
        properties.setAllowedContentTypes(Set.of("image/png", "image/jpeg"));
        properties.setPublicBaseUrl(URI.create("https://m2.invalid"));
        storage = new LocalFileStorage(properties);
        var attachments = new AttachmentService(savedAttachments, storage, properties,
                new AttachmentFormatValidator(List.of(new PngContentValidationStrategy())));
        service = new ExternalAttachmentService(refs, tickets, downloader, attachments,
                new AttachmentReferenceService(properties), properties);
    }
    @ParameterizedTest @ValueSource(strings={"file:///etc/passwd","javascript:alert(1)","//example.test/file","https://user:password@example.test/file","invalid"})
    void rejectsUnsafeOrCredentialBearingReferencesWithoutPerformingIO(String url) {
        assertThatThrownBy(() -> service.validate(List.of(new UpdateTicketStatusRequest.ExternalAttachment("photo.jpg","image/jpeg",url,null))))
                .isInstanceOf(InvalidTicketRequestException.class).hasMessage("Referencia de evidencia externa inválida");
        verifyNoInteractions(refs);
    }
    @ParameterizedTest @ValueSource(strings={"M3","M4","M7","M5","M6","M8"})
    void preservesOptionalSizeAndRecordsEventProvenanceWithInternalVisibility(String area) {
        ticket.setResponsibleAreaId(area);
        var attachments=List.of(new UpdateTicketStatusRequest.ExternalAttachment("photo.jpg","image/jpeg","https://example.test/photo",null));
        service.validate(attachments);
        UUID event=UUID.randomUUID(); var when=Instant.parse("2026-10-01T12:00:00Z");
        var request=new UpdateTicketStatusRequest(ticketId,UpdateTicketStatusType.PROGRESS,null,null,null,null,
                new UpdateTicketStatusRequest.Actor("SYSTEM",null),when,attachments);
        service.record(ticket,event,area,request);
        var captor=org.mockito.ArgumentCaptor.forClass(ExternalAttachmentReference.class);
        verify(refs).save(captor.capture());
        var ref=captor.getValue(); assertThat(ref.getExternalEventId()).isEqualTo(event);
        assertThat(ref.getSizeBytes()).isNull(); assertThat(ref.getVisibility()).isEqualTo(MessageVisibility.INTERNAL);
        assertThat(ref.getUpdateType()).isEqualTo(UpdateTicketStatusType.PROGRESS);
        assertThat(ref.getOccurredAt()).isEqualTo(when);
    }
    @ParameterizedTest @ValueSource(strings={"M3","M4","M7","M5","M6","M8"})
    void ownerSeesOnlyPublicReferencesAndStaffMustHaveTicketScope(String area) {
        ticket.setResponsibleAreaId(area);
        var internal=reference(MessageVisibility.INTERNAL); var publicRef=reference(MessageVisibility.PUBLIC);
        when(refs.findAllByTicket_IdOrderByOccurredAtAscIdAsc(ticketId)).thenReturn(List.of(internal,publicRef));
        assertThat(service.list(ticketId,identity(owner,ModuleRole.CITIZEN,null))).hasSize(1);
        assertThat(service.list(ticketId,identity(UUID.randomUUID(),ModuleRole.AGENT,null))).hasSize(2);
        assertThat(service.list(ticketId,identity(UUID.randomUUID(),ModuleRole.AREA_RESPONSIBLE,area))).hasSize(2);
        assertThatThrownBy(() -> service.list(ticketId,identity(UUID.randomUUID(),ModuleRole.AREA_RESPONSIBLE,area.equals("M6")?"M3":"M6")))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(() -> service.list(ticketId,identity(UUID.randomUUID(),ModuleRole.CITIZEN,null)))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(() -> service.list(ticketId,null)).isInstanceOf(UnauthorizedTicketOperationException.class);
    }

    @Test void linksOneInternalCopyForRepeatedEvidenceAndUsesTheExistingDownloadPermissions() throws Exception {
        byte[] png = validPng();
        var item = evidence();
        when(downloader.download(URI.create(item.url()), 10 * 1024 * 1024, null)).thenReturn(Optional.of(png));
        when(savedAttachments.saveAll(anyList())).thenAnswer(call -> {
            List<Attachment> list = call.getArgument(0);
            list.getFirst().setId(42L);
            return list;
        });
        UUID eventId = UUID.randomUUID();
        service.record(ticket, eventId, "M3", request(List.of(item, item)));

        var captor = org.mockito.ArgumentCaptor.forClass(ExternalAttachmentReference.class);
        verify(refs, times(2)).save(captor.capture());
        var first = captor.getAllValues().getFirst();
        var second = captor.getAllValues().getLast();
        var copy = first.getLocalAttachment();
        assertThat(first.getExternalEventId()).isEqualTo(eventId);
        assertThat(second.getLocalAttachment()).isSameAs(copy);
        assertThat(copy.getVisibility()).isEqualTo(MessageVisibility.INTERNAL);
        assertThat(copy.getSourceModuleId()).isEqualTo("M3");
        verify(downloader).download(URI.create(item.url()), 10 * 1024 * 1024, null);
        verify(savedAttachments).saveAll(anyList());

        when(refs.findAllByTicket_IdOrderByOccurredAtAscIdAsc(ticketId)).thenReturn(List.of(first));
        when(savedAttachments.findById(42L)).thenReturn(Optional.of(copy));
        var area = identity(UUID.randomUUID(), ModuleRole.AREA_RESPONSIBLE, "M3");
        var citizen = identity(owner, ModuleRole.CITIZEN, null);
        assertThat(service.list(ticketId, area).getFirst().localDownloadUrl())
                .isEqualTo("https://m2.invalid/api/attachments/42/content");
        assertThat(service.list(ticketId, citizen)).isEmpty();
        var access = new AttachmentAccessService(savedAttachments, storage);
        assertThat(access.download(42L, area).resource().getContentAsByteArray()).isEqualTo(png);
        assertThatThrownBy(() -> access.download(42L, citizen))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
        assertThatThrownBy(() -> access.download(42L, identity(UUID.randomUUID(), ModuleRole.AREA_RESPONSIBLE, "M4")))
                .isInstanceOf(UnauthorizedTicketOperationException.class);
    }

    @Test void invalidContentLeavesAnInternalReferenceWithoutStorage() {
        when(downloader.download(any(), anyLong(), isNull())).thenReturn(Optional.of(new byte[]{1, 2, 3}));
        service.record(ticket, UUID.randomUUID(), "M3", request(List.of(evidence())));
        verify(refs).save(argThat(ref -> ref.getLocalAttachment() == null
                && ref.getVisibility() == MessageVisibility.INTERNAL));
        verifyNoInteractions(savedAttachments);
    }

    @Test void unavailableFileLeavesOnlyAnInternalReference() {
        when(downloader.download(any(), anyLong(), isNull())).thenReturn(Optional.empty());
        service.record(ticket, UUID.randomUUID(), "M3", request(List.of(evidence())));
        verify(refs).save(argThat(ref -> ref.getLocalAttachment() == null
                && ref.getVisibility() == MessageVisibility.INTERNAL));
        verifyNoInteractions(savedAttachments);
    }

    @Test void aLaterReferenceFailureRemovesTheStoredFileOnTransactionRollback() throws IOException {
        when(downloader.download(any(), anyLong(), isNull())).thenReturn(Optional.of(validPng()));
        var storageKey = new AtomicReference<String>();
        when(savedAttachments.saveAll(anyList())).thenAnswer(call -> {
            List<Attachment> list = call.getArgument(0);
            storageKey.set(list.getFirst().getStorageKey());
            return list;
        });
        when(refs.save(any())).thenThrow(new IllegalStateException("synthetic"));
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThatThrownBy(() -> service.record(ticket, UUID.randomUUID(), "M3", request(List.of(evidence()))))
                    .hasMessage("synthetic");
            assertThat(storage.read(storageKey.get()).sizeBytes()).isPositive();
            TransactionSynchronizationManager.getSynchronizations().forEach(
                    callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThatThrownBy(() -> storage.read(storageKey.get())).isInstanceOf(StoredFileNotFoundException.class);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private byte[] validPng() throws IOException {
        try (var stream = getClass().getResourceAsStream("/attachments/valid.png")) {
            return stream.readAllBytes();
        }
    }

    private UpdateTicketStatusRequest.ExternalAttachment evidence() {
        return new UpdateTicketStatusRequest.ExternalAttachment("photo.png", "image/png", "https://files.example.test/p", null);
    }

    private UpdateTicketStatusRequest request(List<UpdateTicketStatusRequest.ExternalAttachment> items) {
        return new UpdateTicketStatusRequest(ticketId, UpdateTicketStatusType.PROGRESS, null, null, null, null,
                new UpdateTicketStatusRequest.Actor("SYSTEM", null), Instant.parse("2026-10-01T12:00:00Z"), items);
    }
    ExternalAttachmentReference reference(MessageVisibility visibility) {
        var ref=new ExternalAttachmentReference(); ref.setVisibility(visibility); ref.setTicket(ticket);
        ref.setUrl("https://example.test/photo"); return ref;
    }
    AuthenticatedIdentity identity(UUID id,ModuleRole role,String area) {
        return new AuthenticatedIdentity("test",id,"Test",area,role);
    }
}
