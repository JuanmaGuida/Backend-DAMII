package com.reclamos.backend.storage;

import com.reclamos.backend.config.AttachmentProperties;
import com.reclamos.backend.exception.AttachmentStorageUnavailableException;
import com.reclamos.backend.exception.StoredFileNotFoundException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

@Component
public class LocalFileStorage implements FileStorage {
    private final Path root;

    public LocalFileStorage(AttachmentProperties properties) {
        this.root = properties.getStorage().getRoot().toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(UUID ticketId, InputStream content) {
        Objects.requireNonNull(ticketId, "ticketId");
        Objects.requireNonNull(content, "content");

        String storageKey = "tickets/" + ticketId + "/" + UUID.randomUUID();
        Path target = resolveSecurely(storageKey);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            long sizeBytes = Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING);
            if (sizeBytes <= 0) {
                throw new AttachmentStorageUnavailableException();
            }
            moveCompletedFile(temporary, target);
            temporary = null;
            return new StoredFile(storageKey, sizeBytes);
        } catch (IOException exception) {
            throw new AttachmentStorageUnavailableException(exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The primary storage failure is reported by the operation above.
                }
            }
        }
    }

    @Override
    public StoredContent read(String storageKey) {
        Path target = resolveSecurely(storageKey);
        try {
            if (!Files.isRegularFile(target)) {
                throw new StoredFileNotFoundException();
            }
            Path realRoot = root.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRoot) || !Files.isRegularFile(realTarget)) {
                throw new AttachmentStorageUnavailableException();
            }
            long sizeBytes = Files.size(realTarget);
            if (sizeBytes <= 0) {
                throw new StoredFileNotFoundException();
            }
            return new StoredContent(new FileSystemResource(realTarget), sizeBytes);
        } catch (StoredFileNotFoundException | AttachmentStorageUnavailableException exception) {
            throw exception;
        } catch (java.nio.file.NoSuchFileException exception) {
            throw new StoredFileNotFoundException();
        } catch (IOException exception) {
            throw new AttachmentStorageUnavailableException(exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path target = resolveSecurely(storageKey);
        try {
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new AttachmentStorageUnavailableException(exception);
        }
    }

    private Path resolveSecurely(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new AttachmentStorageUnavailableException();
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new AttachmentStorageUnavailableException();
        }
        return resolved;
    }

    private void moveCompletedFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target);
        }
    }
}
