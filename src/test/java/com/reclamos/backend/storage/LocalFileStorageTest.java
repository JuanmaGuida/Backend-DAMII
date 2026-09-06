package com.reclamos.backend.storage;

import com.reclamos.backend.config.AttachmentProperties;
import com.reclamos.backend.exception.AttachmentStorageUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileStorageTest {
    @TempDir
    Path root;

    @Test
    void storesWithUniqueBackendKeysAndDeletesIdempotently() throws Exception {
        LocalFileStorage storage = storage();
        UUID ticketId = UUID.randomUUID();

        StoredFile first = storage.store(ticketId, new ByteArrayInputStream(new byte[]{1, 2, 3}));
        StoredFile second = storage.store(ticketId, new ByteArrayInputStream(new byte[]{4}));

        assertNotEquals(first.storageKey(), second.storageKey());
        assertTrue(first.storageKey().startsWith("tickets/" + ticketId + "/"));
        assertEquals(3, first.sizeBytes());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(root.resolve(first.storageKey())));

        storage.delete(first.storageKey());
        storage.delete(first.storageKey());
        assertFalse(Files.exists(root.resolve(first.storageKey())));
    }

    @Test
    void rejectsStorageKeysOutsideConfiguredRoot() {
        assertThrows(AttachmentStorageUnavailableException.class, () -> storage().delete("../../outside"));
    }

    @Test
    void refusesToPublishAnEmptyStream() throws Exception {
        assertThrows(AttachmentStorageUnavailableException.class,
                () -> storage().store(UUID.randomUUID(), new ByteArrayInputStream(new byte[0])));
        try (var files = Files.walk(root)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }

    private LocalFileStorage storage() {
        AttachmentProperties properties = new AttachmentProperties();
        properties.getStorage().setRoot(root);
        return new LocalFileStorage(properties);
    }
}
