-- Preserve V47 identity/permissions and V48 Outbox history. No historical events are replayed.
ALTER TABLE inbox_events DROP CONSTRAINT ck_inbox_event_type;
ALTER TABLE inbox_events ADD CONSTRAINT ck_inbox_event_type
    CHECK (event_type IN ('updateTicketStatus', 'citizenUpdated'));
ALTER TABLE inbox_events ADD COLUMN payload_hash VARCHAR(64);
ALTER TABLE tickets ADD COLUMN external_update_at TIMESTAMP WITH TIME ZONE;

-- A real citizen may be known through a partial M1 event before the first login.
ALTER TABLE module_users ALTER COLUMN first_name DROP NOT NULL;
ALTER TABLE module_users ALTER COLUMN last_name DROP NOT NULL;
ALTER TABLE module_users ADD COLUMN external_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE module_users ADD COLUMN external_profile_updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE module_users ADD COLUMN external_status_updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE module_users ADD COLUMN external_address_updated_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE module_users ADD CONSTRAINT ck_module_user_external_status
    CHECK (external_status IN ('UNKNOWN', 'ACTIVE', 'BLOCKED', 'DECEASED'));
ALTER TABLE module_users ADD CONSTRAINT ck_module_user_partial_profile CHECK (
    ((identity_source='M1' AND actor_type='CIUDADANO') OR (first_name IS NOT NULL AND last_name IS NOT NULL))
    AND (first_name IS NULL OR length(btrim(first_name)) > 0)
    AND (last_name IS NULL OR length(btrim(last_name)) > 0));
