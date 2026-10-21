package com.reclamos.backend.service;

import java.util.Set;

/** A structural format check; implementations must leave the supplied bytes unchanged. */
public interface AttachmentContentValidationStrategy {
    Set<String> supportedMimeTypes();

    boolean isValid(byte[] content, String declaredMime);
}
