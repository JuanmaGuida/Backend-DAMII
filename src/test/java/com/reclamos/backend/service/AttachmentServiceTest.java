package com.reclamos.backend.service;

import com.reclamos.backend.config.AttachmentProperties;
import com.reclamos.backend.entity.ActorType;
import com.reclamos.backend.entity.Attachment;
import com.reclamos.backend.entity.MessageVisibility;
import com.reclamos.backend.entity.Ticket;
import com.reclamos.backend.exception.AttachmentPayloadTooLargeException;
import com.reclamos.backend.exception.AttachmentStorageUnavailableException;
import com.reclamos.backend.exception.InvalidAttachmentException;
import com.reclamos.backend.exception.UnsupportedAttachmentMediaTypeException;
import com.reclamos.backend.identity.AuthenticatedIdentity;
import com.reclamos.backend.identity.ModuleRole;
import com.reclamos.backend.repository.AttachmentRepository;
import com.reclamos.backend.storage.FileStorage;
import com.reclamos.backend.storage.StoredFile;
import com.reclamos.backend.support.AttachmentFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AttachmentServiceTest {
    private final AttachmentRepository repository = mock(AttachmentRepository.class);
    private final FileStorage storage = mock(FileStorage.class);
    private AttachmentProperties properties;
    private AttachmentService service;

    @BeforeEach
    void setUp() {
        reset(repository, storage);
        properties = properties();
        service = new AttachmentService(repository, storage, properties, AttachmentFixtures.formatValidator());
        when(repository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void acceptsNoEvidenceAndEveryApprovedMimeType() {
        assertTrue(service.validate(null).isEmpty());
        assertTrue(service.validate(new MultipartFile[0]).isEmpty());
        for (String contentType : properties.getAllowedContentTypes()) {
            assertEquals(contentType, service.validate(new MultipartFile[]{file("safe.bin", contentType)})
                    .getFirst().contentType());
        }
    }

    @Test
    void rejectsEmptyNullMaliciousAndUnsupportedFiles() {
        assertThrows(InvalidAttachmentException.class,
                () -> service.validate(new MultipartFile[]{new MockMultipartFile("evidence", new byte[0])}));
        assertThrows(InvalidAttachmentException.class,
                () -> service.validate(new MultipartFile[]{null}));
        assertThrows(InvalidAttachmentException.class,
                () -> service.validate(new MultipartFile[]{file("../../etc/passwd", "image/jpeg")}));
        assertThrows(UnsupportedAttachmentMediaTypeException.class,
                () -> service.validate(new MultipartFile[]{file("malware.zip", "application/zip")}));
    }

    @Test
    void enforcesCountPerFileAndRealTotalLimits() {
        MultipartFile[] six = new MultipartFile[6];
        for (int index = 0; index < six.length; index++) {
            six[index] = sizedFile("f" + index + ".jpg", "image/jpeg", 1);
        }
        assertThrows(AttachmentPayloadTooLargeException.class, () -> service.validate(six));
        assertThrows(AttachmentPayloadTooLargeException.class,
                () -> service.validate(new MultipartFile[]{sizedFile("large.jpg", "image/jpeg",
                        DataSize.ofMegabytes(10).toBytes() + 1)}));

        properties.getLimits().setMaxTotalSize(DataSize.ofMegabytes(15));
        assertThrows(AttachmentPayloadTooLargeException.class,
                () -> service.validate(new MultipartFile[]{
                        sizedFile("one.jpg", "image/jpeg", DataSize.ofMegabytes(8).toBytes()),
                        sizedFile("two.jpg", "image/jpeg", DataSize.ofMegabytes(8).toBytes())
                }));
    }

    @Test
    void persistsAllCitizenMetadataForTheSameTicket() {
        Ticket ticket = ticket();
        AuthenticatedIdentity identity = identity();
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        when(storage.store(eq(ticket.getId()), any(InputStream.class)))
                .thenReturn(new StoredFile("tickets/" + ticket.getId() + "/one", AttachmentFixtures.bytes("image/jpeg").length),
                        new StoredFile("tickets/" + ticket.getId() + "/two", AttachmentFixtures.bytes("application/pdf").length));

        List<Attachment> saved = service.storeForTicket(ticket, identity, service.validate(new MultipartFile[]{
                file("citizen-one.jpg", "image/jpeg"),
                file("citizen-two.pdf", "application/pdf")
        }), now);

        assertEquals(2, saved.size());
        assertTrue(saved.stream().allMatch(attachment -> attachment.getTicket() == ticket));
        assertTrue(saved.stream().allMatch(attachment -> attachment.getVisibility() == MessageVisibility.PUBLIC));
        assertTrue(saved.stream().allMatch(attachment -> attachment.getUploadedByType() == ActorType.CITIZEN));
        assertTrue(saved.stream().allMatch(attachment -> identity.citizenId().toString()
                .equals(attachment.getUploadedById())));
        assertTrue(saved.stream().allMatch(attachment -> attachment.getSourceModuleId() == null));
        assertTrue(saved.stream().allMatch(attachment -> now.equals(attachment.getCreatedAt())));
        assertTrue(saved.stream().noneMatch(attachment -> attachment.getStorageKey()
                .contains(attachment.getFileName())));
    }

    @Test
    void anonymousInitialUploadUsesCitizenActorWithoutInventingAnId() {
        Ticket ticket = ticket();
        Instant now = Instant.parse("2026-09-06T12:00:00Z");
        when(storage.store(eq(ticket.getId()), any(InputStream.class)))
                .thenReturn(new StoredFile("tickets/" + ticket.getId() + "/anonymous", AttachmentFixtures.bytes("image/jpeg").length));

        Attachment saved = service.storeForTicket(ticket, null, service.validate(new MultipartFile[]{
                file("anonymous.jpg", "image/jpeg")
        }), now).getFirst();

        assertEquals(ActorType.CITIZEN, saved.getUploadedByType());
        assertNull(saved.getUploadedById());
        assertNull(saved.getSourceModuleId());
    }

    @Test
    void generalUploadPersistsValidatedStaffActorAndInternalVisibility() {
        Ticket ticket = ticket();
        Instant now = Instant.parse("2026-09-20T12:00:00Z");
        String actorId = UUID.randomUUID().toString();
        when(storage.store(eq(ticket.getId()), any(InputStream.class)))
                .thenReturn(new StoredFile("tickets/" + ticket.getId() + "/internal", AttachmentFixtures.bytes("application/pdf").length));

        Attachment saved = service.storeForTicket(ticket,
                new AttachmentService.UploadActor(ActorType.AREA_RESPONSIBLE, actorId),
                MessageVisibility.INTERNAL,
                service.validate(new MultipartFile[]{file("internal.pdf", "application/pdf")}), now)
                .getFirst();

        assertEquals(MessageVisibility.INTERNAL, saved.getVisibility());
        assertEquals(ActorType.AREA_RESPONSIBLE, saved.getUploadedByType());
        assertEquals(actorId, saved.getUploadedById());
        assertEquals(ticket, saved.getTicket());
        assertEquals(now, saved.getCreatedAt());
    }

    @Test
    void failureOnSecondFileCleansTheFirstAndDoesNotPersistMetadata() {
        Ticket ticket = ticket();
        String firstKey = "tickets/" + ticket.getId() + "/first";
        when(storage.store(eq(ticket.getId()), any(InputStream.class)))
                .thenReturn(new StoredFile(firstKey, AttachmentFixtures.bytes("image/jpeg").length))
                .thenThrow(new AttachmentStorageUnavailableException());

        assertThrows(AttachmentStorageUnavailableException.class,
                () -> service.storeForTicket(ticket, identity(), service.validate(new MultipartFile[]{
                        file("one.jpg", "image/jpeg"), file("two.jpg", "image/jpeg")
                }), Instant.now()));

        verify(storage).delete(firstKey);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void failureOnFirstFileDoesNotPersistMetadata() {
        Ticket ticket = ticket();
        when(storage.store(eq(ticket.getId()), any(InputStream.class)))
                .thenThrow(new AttachmentStorageUnavailableException());

        assertThrows(AttachmentStorageUnavailableException.class,
                () -> service.storeForTicket(ticket, identity(),
                        service.validate(new MultipartFile[]{file("one.jpg", "image/jpeg")}), Instant.now()));

        verify(storage, never()).delete(any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void metadataFailureCleansStoredFilesWithoutHidingOriginalError() {
        Ticket ticket = ticket();
        String key = "tickets/" + ticket.getId() + "/file";
        when(storage.store(eq(ticket.getId()), any(InputStream.class))).thenReturn(new StoredFile(key, AttachmentFixtures.bytes("image/jpeg").length));
        IllegalStateException original = new IllegalStateException("database failure");
        when(repository.saveAll(any())).thenThrow(original);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.storeForTicket(ticket, identity(),
                        service.validate(new MultipartFile[]{file("one.jpg", "image/jpeg")}), Instant.now()));

        assertSame(original, thrown);
        verify(storage).delete(key);
    }

    @Test
    void cleanupFailureDoesNotReplaceTheOriginalFailure() {
        Ticket ticket = ticket();
        String key = "tickets/" + ticket.getId() + "/file";
        when(storage.store(eq(ticket.getId()), any(InputStream.class))).thenReturn(new StoredFile(key, AttachmentFixtures.bytes("image/jpeg").length));
        IllegalStateException original = new IllegalStateException("database failure");
        when(repository.saveAll(any())).thenThrow(original);
        doThrow(new AttachmentStorageUnavailableException()).when(storage).delete(key);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.storeForTicket(ticket, identity(),
                        service.validate(new MultipartFile[]{file("one.jpg", "image/jpeg")}), Instant.now()));

        assertSame(original, thrown);
    }

    @Test
    void transactionRollbackCleansStoredFileAndRepeatedCleanupIsSafe() {
        Ticket ticket = ticket();
        String key = "tickets/" + ticket.getId() + "/file";
        when(storage.store(eq(ticket.getId()), any(InputStream.class))).thenReturn(new StoredFile(key, AttachmentFixtures.bytes("image/jpeg").length));
        TransactionSynchronizationManager.initSynchronization();

        service.storeForTicket(ticket, identity(),
                service.validate(new MultipartFile[]{file("one.jpg", "image/jpeg")}), Instant.now());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(storage, times(2)).delete(key);
    }

    @Test
    void invalidBatchNeverWritesEvenWhenTheFirstFileIsValid() {
        MultipartFile[] files = {file("valid.png", "image/png"),
                new MockMultipartFile("evidence", "fake.png", "image/png", new byte[12])};
        assertThrows(InvalidAttachmentException.class,
                () -> service.storeForTicket(ticket(), identity(), service.validate(files), Instant.now()));
        verifyNoInteractions(storage);
        verify(repository, never()).saveAll(any());
    }

    @Test
    void extensionDoesNotOverrideContentAndDeclaredMimeMustMatch() {
        assertEquals("image/png", service.validate(new MultipartFile[]{
                file("misleading.exe", "image/png")}).getFirst().contentType());
        assertThrows(InvalidAttachmentException.class, () -> service.validate(new MultipartFile[]{
                new MockMultipartFile("evidence", "photo.png", "image/png", AttachmentFixtures.bytes("image/jpeg"))}));
        assertThrows(InvalidAttachmentException.class, () -> service.validate(new MultipartFile[]{
                new MockMultipartFile("evidence", "image.png", "image/png", "MZ executable".getBytes())}));
    }

    @Test
    void enforcesExactSizeLimitsAndStoresOnlyTheValidatedSnapshot() throws Exception {
        byte[] bytes = AttachmentFixtures.bytes("image/png");
        properties.getLimits().setMaxFileSize(DataSize.ofBytes(bytes.length));
        properties.getLimits().setMaxTotalSize(DataSize.ofBytes(bytes.length));
        MultipartFile source = mock(MultipartFile.class);
        when(source.getSize()).thenReturn((long) bytes.length);
        when(source.getContentType()).thenReturn("image/png");
        when(source.getOriginalFilename()).thenReturn("proof.png");
        when(source.getInputStream()).thenReturn(new java.io.ByteArrayInputStream(bytes))
                .thenThrow(new java.io.IOException("must never reopen"));
        var validated = service.validate(new MultipartFile[]{source});
        Ticket ticket = ticket();
        when(storage.store(eq(ticket.getId()), any(InputStream.class))).thenAnswer(invocation -> {
            assertArrayEquals(bytes, ((InputStream) invocation.getArgument(1)).readAllBytes());
            return new StoredFile("snapshot", bytes.length);
        });
        service.storeForTicket(ticket, identity(), validated, Instant.now());
        verify(source, times(1)).getInputStream();
        properties.getLimits().setMaxFileSize(DataSize.ofBytes(bytes.length - 1));
        assertThrows(AttachmentPayloadTooLargeException.class,
                () -> service.validate(new MultipartFile[]{file("too-large.png", "image/png")}));
    }

    @Test
    void rejectsUnreadableStreamsAndDeclaredSizeMismatchWithoutStorage() throws Exception {
        MultipartFile source = sizedFile("proof.png", "image/png", 12);
        when(source.getInputStream()).thenThrow(new java.io.IOException("private disk details"));
        var error = assertThrows(InvalidAttachmentException.class,
                () -> service.validate(new MultipartFile[]{source}));
        assertFalse(error.getMessage().contains("private"));
        doReturn(new java.io.ByteArrayInputStream(new byte[0])).when(source).getInputStream();
        assertThrows(InvalidAttachmentException.class, () -> service.validate(new MultipartFile[]{source}));
        doReturn(new java.io.ByteArrayInputStream(AttachmentFixtures.bytes("image/png"))).when(source).getInputStream();
        assertThrows(InvalidAttachmentException.class, () -> service.validate(new MultipartFile[]{source}));
        properties.getLimits().setMaxFileSize(DataSize.ofBytes(12));
        doReturn(new java.io.ByteArrayInputStream(new byte[13])).when(source).getInputStream();
        assertThrows(AttachmentPayloadTooLargeException.class, () -> service.validate(new MultipartFile[]{source}));
        verifyNoInteractions(storage);
    }

    private AttachmentProperties properties() {
        AttachmentProperties value = new AttachmentProperties();
        value.getLimits().setMaxFiles(5);
        value.getLimits().setMaxFileSize(DataSize.ofMegabytes(10));
        value.getLimits().setMaxTotalSize(DataSize.ofMegabytes(50));
        value.setAllowedContentTypes(Set.of("image/jpeg", "image/png", "image/webp", "image/heic",
                "image/heif", "application/pdf"));
        return value;
    }

    private MockMultipartFile file(String name, String contentType) {
        return contentType.equals("application/zip")
                ? new MockMultipartFile("evidence", name, contentType, new byte[]{1})
                : AttachmentFixtures.file("evidence", name, contentType);
    }

    private MultipartFile sizedFile(String name, String contentType, long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn(name);
        when(file.getContentType()).thenReturn(contentType);
        when(file.getSize()).thenReturn(size);
        when(file.isEmpty()).thenReturn(false);
        return file;
    }

    private Ticket ticket() {
        Ticket ticket = new Ticket();
        ticket.setId(UUID.randomUUID());
        return ticket;
    }

    private AuthenticatedIdentity identity() {
        return new AuthenticatedIdentity("subject", UUID.randomUUID(), "Citizen", null, ModuleRole.CITIZEN);
    }
}
