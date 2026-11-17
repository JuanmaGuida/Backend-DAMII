package com.reclamos.backend.outbox;

/** Fixed diagnostics only. Never retain broker text, payload, connection URI or cause. */
public final class PublicationFailure extends RuntimeException {
    public enum Code { INVALID_PAYLOAD, UNVERIFIED_IDENTITY, BROKER_UNAVAILABLE, BROKER_NACK,
        CONFIRM_TIMEOUT, UNROUTABLE, INTERRUPTED, PUBLICATION_ERROR }
    private final Code code;
    public PublicationFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
