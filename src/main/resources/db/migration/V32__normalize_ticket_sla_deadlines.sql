DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM tickets ticket
        WHERE ticket.first_response_due_at IS DISTINCT FROM (
            SELECT sla.due_at
            FROM ticket_sla sla
            WHERE sla.ticket_id = ticket.id
              AND sla.sla_type = 'FIRST_RESPONSE'
            ORDER BY sla.cycle_number DESC
            LIMIT 1
        )
    ) THEN
        RAISE EXCEPTION
            'V32 cannot drop tickets.first_response_due_at: TicketSla is not an equivalent source';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tickets ticket
        WHERE ticket.resolution_due_at IS DISTINCT FROM (
            SELECT sla.due_at
            FROM ticket_sla sla
            WHERE sla.ticket_id = ticket.id
              AND sla.sla_type = 'RESOLUTION'
            ORDER BY sla.cycle_number DESC
            LIMIT 1
        )
    ) THEN
        RAISE EXCEPTION
            'V32 cannot drop tickets.resolution_due_at: TicketSla is not an equivalent source';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM tickets
        WHERE escalation_reason_code = 'SLA_NEAR_DUE'
    ) THEN
        RAISE EXCEPTION
            'V32 cannot remove SLA_NEAR_DUE as escalation reason while legacy rows still use it';
    END IF;
END $$;

DROP INDEX IF EXISTS idx_ticket_resolution_due_at;

ALTER TABLE tickets
    DROP COLUMN first_response_due_at,
    DROP COLUMN resolution_due_at;

ALTER TABLE tickets
    DROP CONSTRAINT ck_ticket_escalation_reason;

ALTER TABLE tickets
    ADD CONSTRAINT ck_ticket_escalation_reason CHECK (
        escalation_reason_code IS NULL
        OR escalation_reason_code IN ('CRITICAL_PRIORITY', 'SLA_BREACHED', 'MANUAL')
    );
