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
import com.reclamos.backend.repository.AttachmentRepository;
import com.reclamos.backend.storage.FileStorage;
import com.reclamos.backend.storage.StoredFile;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AttachmentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AttachmentService.class);

    private final AttachmentRepository attachmentRepository;
    private final FileStorage fileStorage;
    private final AttachmentProperties properties;

    public List<ValidatedAttachment> validate(MultipartFile[] evidence) {
        if (evidence == null || evidence.length == 0) {
            return List.of();
        }
        if (evidence.length > properties.getLimits().getMaxFiles()) {
            throw new AttachmentPayloadTooLargeException("La cantidad de archivos supera el máximo permitido.");
        }

        long maxFileSize = properties.getLimits().getMaxFileSize().toBytes();
        long maxTotalSize = properties.getLimits().getMaxTotalSize().toBytes();
        Set<String> allowedTypes = properties.getAllowedContentTypes();
        List<ValidatedAttachment> validated = new ArrayList<>(evidence.length);
        long totalSize = 0;

        for (MultipartFile file : evidence) {
            if (file == null || file.isEmpty() || file.getSize() <= 0) {
                throw new InvalidAttachmentException("Cada evidencia debe contener un archivo no vacío.");
            }
            if (file.getSize() > maxFileSize) {
                throw new AttachmentPayloadTooLargeException("Un archivo supera el tamaño máximo permitido.");
            }
            try {
                totalSize = Math.addExact(totalSize, file.getSize());
            } catch (ArithmeticException exception) {
                throw new AttachmentPayloadTooLargeException("El tamaño total de la evidencia supera el máximo permitido.");
            }
            if (totalSize > maxTotalSize) {
                throw new AttachmentPayloadTooLargeException("El tamaño total de la evidencia supera el máximo permitido.");
            }

            String contentType = normalizeContentType(file.getContentType());
            if (!allowedTypes.contains(contentType)) {
                throw new UnsupportedAttachmentMediaTypeException("El tipo de archivo no está permitido.");
            }
            normalizeFileName(file.getOriginalFilename());
        }
        for (MultipartFile file : evidence) {
            String contentType = normalizeContentType(file.getContentType());
            byte[] content = readContent(file, maxFileSize);
            AttachmentFormatValidator.validate(content, contentType);
            validated.add(new ValidatedAttachment(content, normalizeFileName(file.getOriginalFilename()), contentType));
        }
        return List.copyOf(validated);
    }

    public List<Attachment> storeForTicket(Ticket ticket, AuthenticatedIdentity identity,
                                           List<ValidatedAttachment> validated, Instant createdAt) {
        return storeForTicket(ticket,
                new UploadActor(ActorType.CITIZEN,
                        identity == null ? null : identity.citizenId().toString()),
                MessageVisibility.PUBLIC, validated, createdAt);
    }

    public List<Attachment> storeForTicket(Ticket ticket, UploadActor actor, MessageVisibility visibility,
                                           List<ValidatedAttachment> validated, Instant createdAt) {
        if (validated.isEmpty()) {
            return List.of();
        }

        List<String> storedKeys = new ArrayList<>(validated.size());
        registerRollbackCleanup(storedKeys);
        List<Attachment> attachments = new ArrayList<>(validated.size());
        try {
            for (ValidatedAttachment item : validated) {
                StoredFile stored = store(ticket, item);
                storedKeys.add(stored.storageKey());
                if (stored.sizeBytes() != item.sizeBytes()) {
                    throw new AttachmentStorageUnavailableException();
                }
                attachments.add(toEntity(ticket, actor, visibility, item, stored, createdAt));
            }
            return attachmentRepository.saveAll(attachments);
        } catch (RuntimeException exception) {
            cleanup(storedKeys);
            throw exception;
        }
    }

    private StoredFile store(Ticket ticket, ValidatedAttachment item) {
        try (InputStream content = new ByteArrayInputStream(item.content)) {
            return fileStorage.store(ticket.getId(), content);
        } catch (IOException exception) {
            throw new AttachmentStorageUnavailableException(exception);
        }
    }

    private Attachment toEntity(Ticket ticket, UploadActor actor, MessageVisibility visibility,
                                ValidatedAttachment item, StoredFile stored, Instant createdAt) {
        Attachment attachment = new Attachment();
        attachment.setTicket(ticket);
        attachment.setFileName(item.fileName());
        attachment.setContentType(item.contentType());
        attachment.setSizeBytes(stored.sizeBytes());
        attachment.setStorageKey(stored.storageKey());
        attachment.setVisibility(visibility);
        attachment.setUploadedByType(actor.type());
        attachment.setUploadedById(actor.id());
        attachment.setSourceModuleId(null);
        attachment.setCreatedAt(createdAt);
        return attachment;
    }

    private void registerRollbackCleanup(List<String> storedKeys) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    cleanup(storedKeys);
                }
            }
        });
    }

    private void cleanup(List<String> storedKeys) {
        for (String storageKey : storedKeys) {
            try {
                fileStorage.delete(storageKey);
            } catch (RuntimeException cleanupFailure) {
                LOGGER.error("No se pudo eliminar el archivo huérfano con storageKey={}", storageKey,
                        cleanupFailure);
            }
        }
    }

    private String normalizeContentType(String contentType) {
        return contentType == null ? "" : contentType.strip().toLowerCase(Locale.ROOT);
    }

    private String normalizeFileName(String originalFileName) {
        if (originalFileName == null) {
            throw new InvalidAttachmentException("El archivo debe incluir un nombre válido.");
        }
        String fileName = originalFileName.strip();
        boolean invalid = fileName.isBlank()
                || fileName.length() > 255
                || fileName.equals(".")
                || fileName.equals("..")
                || fileName.indexOf('/') >= 0
                || fileName.indexOf('\\') >= 0
                || fileName.chars().anyMatch(Character::isISOControl);
        if (invalid) {
            throw new InvalidAttachmentException("El archivo debe incluir un nombre válido.");
        }
        return fileName;
    }

    private byte[] readContent(MultipartFile file, long maximum) {
        // Never allocate according to an untrusted multipart size or read an unbounded stream.
        int limit = (int) Math.min(maximum, Integer.MAX_VALUE - 1L);
        try (InputStream input = file.getInputStream()) {
            byte[] content = input.readNBytes(limit + 1);
            if (content.length > maximum) {
                throw new AttachmentPayloadTooLargeException("Un archivo supera el tamaño máximo permitido.");
            }
            if (content.length == 0 || content.length != file.getSize()) {
                throw new InvalidAttachmentException("El archivo está vacío o incompleto.");
            }
            return content;
        } catch (IOException exception) {
            throw new InvalidAttachmentException("No se pudo leer el archivo adjunto.");
        }
    }

    /** Immutable snapshot: storage receives exactly the bytes whose format was checked. */
    public static final class ValidatedAttachment {
        private final byte[] content;
        private final String fileName;
        private final String contentType;

        private ValidatedAttachment(byte[] content, String fileName, String contentType) {
            this.content = content;
            this.fileName = fileName;
            this.contentType = contentType;
        }

        public String fileName() { return fileName; }
        public String contentType() { return contentType; }
        public long sizeBytes() { return content.length; }
    }

    public record UploadActor(ActorType type, String id) {
    }
}
