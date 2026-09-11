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
        service = new AttachmentService(repository, storage, properties);
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
            assertEquals(contentType, service.validate(new MultipartFile[]{file("safe.bin", contentType, 1)})
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
                () -> service.validate(new MultipartFile[]{file("../../etc/passwd", "image/jpeg", 1)}));
        assertThrows(UnsupportedAttachmentMediaTypeException.class,
                () -> service.validate(new MultipartFile[]{file("malware.zip", "application/zip", 1)}));
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
                .thenReturn(new StoredFile("tickets/" + ticket.getId() + "/one", 1),
                        new StoredFile("tickets/" + ticket.getId() + "/two", 1));

        List<Attachment> saved = service.storeForTicket(ticket, identity, service.validate(new MultipartFile[]{
                file("citizen-one.jpg", "image/jpeg", 1),
                file("citizen-two.pdf", "application/pdf", 1)
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
    void failureOnSecondFileCleansTheFirstAndDoesNotPersistMetadata() {
        Ticket ticket = ticket();
        String firstKey = "tickets/" + ticket.getId() + "/first";
        when(storage.store(eq(ticket.getId()), any(InputStream.class)))
                .thenReturn(new StoredFile(firstKey, 1))
                .thenThrow(new AttachmentStorageUnavailableException());

        assertThrows(AttachmentStorageUnavailableException.class,
                () -> service.storeForTicket(ticket, identity(), service.validate(new MultipartFile[]{
                        file("one.jpg", "image/jpeg", 1), file("two.jpg", "image/jpeg", 1)
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
                        service.validate(new MultipartFile[]{file("one.jpg", "image/jpeg", 1)}), Instant.now()));

        verify(storage, never()).delete(any());
        verify(repository, never()).saveAll(any());
    }

    @Test
    void metadataFailureCleansStoredFilesWithoutHidingOriginalError() {
        Ticket ticket = ticket();
        String key = "tickets/" + ticket.getId() + "/file";
        when(storage.store(eq(ticket.getId()), any(InputStream.class))).thenReturn(new StoredFile(key, 1));
        IllegalStateException original = new IllegalStateException("database failure");
        when(repository.saveAll(any())).thenThrow(original);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> service.storeForTicket(ticket, identity(),
                        service.validate(new MultipartFile[]{file("one.jpg", "image/jpeg", 1)}), Instant.now()));

        assertSame(original, thrown);
        verify(storage).delete(key);
    }

    @Test
    void cleanupFailureDoesNotReplaceTheOriginalFailure() {
        Ticket ticket = ticket();
        String key = "tickets/" + ticket.getId() + "/file";
        when(storage.store(eq(ticket.getId()), any(InputStream.class))).thenReturn(new StoredFile(key, 1));
        IllegalStateException original = new IllegalStateException("database failure");
        when(repository.saveAll(any())).thenThrow(original);
        doThrow(new AttachmentStorageUnavailableException()).when(storage).delete(key);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.storeForTicket(ticket, identity(),
                        service.validate(new MultipartFile[]{file("one.jpg", "image/jpeg", 1)}), Instant.now()));

        assertSame(original, thrown);
    }

    @Test
    void transactionRollbackCleansStoredFileAndRepeatedCleanupIsSafe() {
        Ticket ticket = ticket();
        String key = "tickets/" + ticket.getId() + "/file";
        when(storage.store(eq(ticket.getId()), any(InputStream.class))).thenReturn(new StoredFile(key, 1));
        TransactionSynchronizationManager.initSynchronization();

        service.storeForTicket(ticket, identity(),
                service.validate(new MultipartFile[]{file("one.jpg", "image/jpeg", 1)}), Instant.now());
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        synchronizations.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verify(storage, times(2)).delete(key);
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

    private MockMultipartFile file(String name, String contentType, int size) {
        return new MockMultipartFile("evidence", name, contentType, new byte[size]);
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
