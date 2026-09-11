ALTER TABLE tickets
    ADD CONSTRAINT ck_ticket_escalation_consistency CHECK (
        (is_escalated = FALSE AND escalation_reason_code IS NULL AND escalated_at IS NULL)
        OR
        (is_escalated = TRUE AND escalation_reason_code IS NOT NULL AND escalated_at IS NOT NULL)
    );
