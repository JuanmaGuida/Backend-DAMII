package com.reclamos.backend.storage;

import java.io.InputStream;
import java.util.UUID;

public interface FileStorage {
    StoredFile store(UUID ticketId, InputStream content);

    void delete(String storageKey);
}
