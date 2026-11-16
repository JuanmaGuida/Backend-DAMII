ALTER TABLE tickets
    ADD COLUMN tracking_code_ciphertext BYTEA,
    ADD COLUMN tracking_code_nonce BYTEA,
    ADD COLUMN tracking_code_key_id VARCHAR(64);

-- All-null also admits historical tickets and writes by an older application.
-- Explicit IS NOT NULL checks prevent SQL UNKNOWN from accepting partial tuples.
ALTER TABLE tickets ADD CONSTRAINT ck_ticket_recoverable_tracking_code CHECK (
    (tracking_code_ciphertext IS NULL AND tracking_code_nonce IS NULL AND tracking_code_key_id IS NULL)
    OR
    (tracking_code_ciphertext IS NOT NULL AND tracking_code_nonce IS NOT NULL AND tracking_code_key_id IS NOT NULL
        AND octet_length(tracking_code_ciphertext) = 48
        AND octet_length(tracking_code_nonce) = 12
        AND tracking_code_key_id ~ '^[A-Za-z0-9._-]{1,64}$'
        AND is_anonymous = FALSE AND citizen_id IS NOT NULL)
);

CREATE UNIQUE INDEX uk_ticket_tracking_key_nonce
    ON tickets (tracking_code_key_id, tracking_code_nonce)
    WHERE tracking_code_key_id IS NOT NULL AND tracking_code_nonce IS NOT NULL;
