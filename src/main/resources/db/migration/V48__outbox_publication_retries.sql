-- Additive only: does not approve, delete, publish or change the status of old events.
ALTER TABLE outbox_events ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE outbox_events ADD COLUMN last_failure_code VARCHAR(40);
