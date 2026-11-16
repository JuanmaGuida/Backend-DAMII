-- No request bodies, files, tracking codes or anonymous passwords are retained.
-- Reservation and completion belong to the SAME ticket creation transaction.
CREATE TABLE ticket_creation_idempotency (
    identity_scope VARCHAR(64) NOT NULL,
    key_hash VARCHAR(64) NOT NULL CHECK (key_hash ~ '^[0-9a-f]{64}$'),
    request_hash VARCHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    ticket_id UUID REFERENCES tickets(id),
    public_id VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (identity_scope, key_hash),
    CHECK ((ticket_id IS NULL) = (public_id IS NULL))
);
-- No expiration: retain for the lifetime of the ticket; do not periodically delete.
