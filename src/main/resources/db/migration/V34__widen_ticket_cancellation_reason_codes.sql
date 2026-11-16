ALTER TABLE ticket_cancellations DROP CONSTRAINT ck_ticket_cancellation_reason;

ALTER TABLE ticket_cancellations ADD CONSTRAINT ck_ticket_cancellation_reason CHECK (
    reason_code IN (
        'INFO_TIMEOUT', 'OUT_OF_SCOPE', 'DOES_NOT_APPLY', 'INVALID_REQUEST_TYPE',
        'INVALID_DATA', 'REJECTED_BY_AREA', 'WITHDRAWN_BY_CITIZEN', 'DATA_ENTRY_ERROR', 'OTHER'
    )
);
