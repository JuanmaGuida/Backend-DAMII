-- V29 introduced ticket_sla without reconstructing cycles for tickets that
-- already existed. FIRST_RESPONSE cannot be rebuilt truthfully after the fact
-- (its milestone may already have happened and business-calendar history is
-- not persisted). This project currently uses disposable DEV/TEST databases,
-- so a populated pre-V30 database must be recreated instead of receiving
-- fabricated SLA history.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM tickets) THEN
        RAISE EXCEPTION
            'V30 requires an empty disposable DEV/TEST ticket database; recreate it so TicketSla starts at ticket creation';
    END IF;
END $$;

ALTER TABLE ticket_sla DROP CONSTRAINT ck_ticket_sla_status;
ALTER TABLE ticket_sla ADD CONSTRAINT ck_ticket_sla_status
    CHECK (status IN ('RUNNING', 'NEAR_DUE', 'MET', 'BREACHED', 'STOPPED'));

ALTER TABLE ticket_sla DROP CONSTRAINT ck_ticket_sla_completed_status;
ALTER TABLE ticket_sla ADD CONSTRAINT ck_ticket_sla_completed_status CHECK (
    (completed_at IS NULL OR status IN ('MET', 'BREACHED', 'STOPPED'))
    AND (status NOT IN ('MET', 'STOPPED') OR completed_at IS NOT NULL)
);

ALTER TABLE ticket_sla ADD CONSTRAINT ck_ticket_sla_paused_state CHECK (
    paused_at IS NULL
    OR (
        sla_type = 'RESOLUTION'
        AND completed_at IS NULL
        AND status IN ('RUNNING', 'NEAR_DUE')
    )
);

ALTER TABLE ticket_sla ADD CONSTRAINT ck_ticket_sla_completed_not_paused CHECK (
    completed_at IS NULL OR paused_at IS NULL
);

ALTER TABLE ticket_sla ADD CONSTRAINT ck_ticket_sla_first_response_cycle CHECK (
    sla_type <> 'FIRST_RESPONSE' OR cycle_number = 1
);

DROP INDEX idx_ticket_sla_near_due_candidates;
CREATE INDEX idx_ticket_sla_near_due_candidates
    ON ticket_sla (near_due_at, ticket_id, id)
    WHERE completed_at IS NULL AND paused_at IS NULL AND status = 'RUNNING';

DROP INDEX idx_ticket_sla_breach_candidates;
CREATE INDEX idx_ticket_sla_breach_candidates
    ON ticket_sla (due_at, ticket_id, id)
    WHERE completed_at IS NULL AND paused_at IS NULL AND status IN ('RUNNING', 'NEAR_DUE');
