package com.reclamos.backend.dto;

/** Body and validator captured in the same transaction. */
public record ResourceRevision<T>(T body, String etag) { }
