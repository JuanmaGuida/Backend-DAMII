CREATE TABLE ticket_sla (
    id BIGSERIAL PRIMARY KEY,
    ticket_id UUID NOT NULL,
    sla_type VARCHAR(30) NOT NULL,
    cycle_number INTEGER NOT NULL,
    policy_id BIGINT NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    near_due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    due_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) NOT NULL,
    paused_at TIMESTAMP WITH TIME ZONE,
    total_paused_seconds BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_ticket_sla_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
    CONSTRAINT fk_ticket_sla_policy FOREIGN KEY (policy_id) REFERENCES sla_policies(id),
    CONSTRAINT uk_ticket_sla_cycle UNIQUE (ticket_id, sla_type, cycle_number),
    CONSTRAINT ck_ticket_sla_type CHECK (sla_type IN ('FIRST_RESPONSE', 'RESOLUTION')),
    CONSTRAINT ck_ticket_sla_status CHECK (status IN ('RUNNING', 'NEAR_DUE', 'MET', 'BREACHED')),
    CONSTRAINT ck_ticket_sla_cycle_number CHECK (cycle_number > 0),
    CONSTRAINT ck_ticket_sla_near_due CHECK (started_at <= near_due_at),
    CONSTRAINT ck_ticket_sla_due CHECK (near_due_at <= due_at),
    CONSTRAINT ck_ticket_sla_completed CHECK (completed_at IS NULL OR completed_at >= started_at),
    CONSTRAINT ck_ticket_sla_paused CHECK (paused_at IS NULL OR paused_at >= started_at),
    CONSTRAINT ck_ticket_sla_total_paused CHECK (total_paused_seconds >= 0),
    CONSTRAINT ck_ticket_sla_completed_status CHECK (
        (completed_at IS NULL OR status IN ('MET', 'BREACHED'))
        AND (status <> 'MET' OR completed_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_ticket_sla_active_type
    ON ticket_sla (ticket_id, sla_type)
    WHERE completed_at IS NULL;

CREATE INDEX idx_ticket_sla_near_due_candidates
    ON ticket_sla (near_due_at)
    WHERE sla_type = 'RESOLUTION' AND completed_at IS NULL AND status = 'RUNNING';

CREATE INDEX idx_ticket_sla_breach_candidates
    ON ticket_sla (due_at)
    WHERE sla_type = 'RESOLUTION' AND completed_at IS NULL AND status IN ('RUNNING', 'NEAR_DUE');

CREATE INDEX idx_ticket_sla_history
    ON ticket_sla (ticket_id, sla_type, cycle_number DESC);

ALTER TABLE ticket_activities DROP CONSTRAINT ck_ticket_activity_action_type;
ALTER TABLE ticket_activities ADD CONSTRAINT ck_ticket_activity_action_type CHECK (
    action_type IN (
        'TICKET_CREATED', 'REVIEW_STARTED', 'STATE_CHANGED', 'REQUEST_TYPE_CHANGED', 'ROUTED',
        'RETURNED_BY_AREA', 'PRIORITY_CHANGED', 'SLA_NEAR_DUE', 'SLA_BREACHED', 'ESCALATED',
        'INFORMATION_REQUIRED', 'INFORMATION_PROVIDED', 'PROGRESS_REPORTED', 'DUPLICATE_LINKED',
        'RESOLVED', 'REOPENED', 'CANCELLATION_REQUESTED', 'CANCELLATION_APPROVED',
        'CANCELLATION_REJECTED', 'CANCELLED', 'CLOSED', 'PUBLIC_MESSAGE_SENT',
        'INTERNAL_MESSAGE_ADDED', 'ATTACHMENT_ADDED'
    )
);
