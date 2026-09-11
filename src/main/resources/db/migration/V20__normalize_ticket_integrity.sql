ALTER TABLE tickets
    ADD CONSTRAINT fk_ticket_citizen_module_user
        FOREIGN KEY (citizen_id) REFERENCES module_users (citizen_id);

CREATE INDEX idx_ticket_resolution_confirmation_due_at
    ON tickets (resolution_confirmation_due_at)
    WHERE current_status = 'RESOLVED'
      AND resolution_confirmation_due_at IS NOT NULL;

DROP INDEX idx_ticket_activity_ticket_sequence;
