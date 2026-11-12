package com.reclamos.backend.inbox;

/** Sanitized codes only: broker payloads can contain internal messages and personal data. */
public final class IncomingEventFailure extends RuntimeException {
    public enum Code { INVALID_EVENT, UNSUPPORTED_VERSION, WRONG_PRODUCER, EVENT_ID_COLLISION,
        PREMATURE_PROGRESS, EXTERNAL_ATTACHMENTS_UNSUPPORTED }
    private final Code code;
    public IncomingEventFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
