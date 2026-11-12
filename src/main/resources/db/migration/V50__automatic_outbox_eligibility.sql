-- No historical ticket/event is enrolled. Mode changes never backfill these flags.
ALTER TABLE tickets ADD COLUMN core_origin_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE outbox_events ADD COLUMN auto_publish_eligible BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_outbox_auto_pending ON outbox_events (id)
    WHERE status = 'PENDING' AND auto_publish_eligible = TRUE;
