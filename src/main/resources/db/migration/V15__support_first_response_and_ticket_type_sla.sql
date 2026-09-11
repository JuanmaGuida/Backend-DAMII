ALTER TABLE tickets ADD COLUMN first_response_due_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE sla_policies ADD COLUMN sla_type VARCHAR(30);
ALTER TABLE sla_policies ADD COLUMN ticket_type VARCHAR(30);
ALTER TABLE sla_policies ADD COLUMN deadline_rule VARCHAR(30);
ALTER TABLE sla_policies ADD COLUMN duration_business_days INTEGER;

UPDATE sla_policies
SET sla_type = 'RESOLUTION', deadline_rule = 'HOURS';

ALTER TABLE sla_policies ALTER COLUMN sla_type SET NOT NULL;
ALTER TABLE sla_policies ALTER COLUMN deadline_rule SET NOT NULL;
ALTER TABLE sla_policies ALTER COLUMN priority DROP NOT NULL;
ALTER TABLE sla_policies ALTER COLUMN duration_seconds DROP NOT NULL;
ALTER TABLE sla_policies ALTER COLUMN reset_on_routing DROP NOT NULL;
ALTER TABLE sla_policies DROP CONSTRAINT uk_sla_policy_priority;
ALTER TABLE sla_policies DROP CONSTRAINT ck_sla_policy_duration;
ALTER TABLE sla_policies DROP CONSTRAINT ck_sla_policy_critical_mode;

ALTER TABLE sla_policies ADD CONSTRAINT ck_sla_policy_type
    CHECK (sla_type IN ('FIRST_RESPONSE', 'RESOLUTION'));
ALTER TABLE sla_policies ADD CONSTRAINT ck_sla_policy_ticket_type
    CHECK (ticket_type IS NULL OR ticket_type IN ('INQUIRY', 'SUGGESTION'));
ALTER TABLE sla_policies ADD CONSTRAINT ck_sla_policy_selector CHECK (
    (priority IS NOT NULL AND ticket_type IS NULL)
        OR (priority IS NULL AND ticket_type IS NOT NULL AND sla_type = 'RESOLUTION')
    );
ALTER TABLE sla_policies ADD CONSTRAINT ck_sla_policy_deadline_rule
    CHECK (deadline_rule IN ('HOURS', 'BUSINESS_DAYS', 'SAME_BUSINESS_DAY'));
ALTER TABLE sla_policies ADD CONSTRAINT ck_sla_policy_duration CHECK (
    (deadline_rule = 'HOURS' AND duration_seconds > 0 AND duration_business_days IS NULL)
        OR (deadline_rule = 'BUSINESS_DAYS' AND duration_seconds IS NULL AND duration_business_days > 0)
        OR (deadline_rule = 'SAME_BUSINESS_DAY' AND duration_seconds IS NULL AND duration_business_days IS NULL)
    );
ALTER TABLE sla_policies ADD CONSTRAINT ck_sla_policy_rule_mode CHECK (
    deadline_rule = 'HOURS' OR mode = 'BUSINESS_HOURS'
    );
ALTER TABLE sla_policies ADD CONSTRAINT ck_sla_policy_ticket_type_rule CHECK (
    ticket_type IS NULL
        OR (ticket_type = 'INQUIRY' AND deadline_rule = 'SAME_BUSINESS_DAY')
        OR (ticket_type = 'SUGGESTION' AND deadline_rule = 'BUSINESS_DAYS')
    );
ALTER TABLE sla_policies ADD CONSTRAINT ck_sla_policy_priority_mode CHECK (
    priority IS NULL
        OR (priority = 'CRITICAL' AND mode = 'CONTINUOUS_24X7')
        OR (priority IN ('LOW', 'MEDIUM', 'HIGH') AND mode = 'BUSINESS_HOURS')
    );

CREATE UNIQUE INDEX uk_sla_policy_priority_type
    ON sla_policies (priority, sla_type) WHERE priority IS NOT NULL;
CREATE UNIQUE INDEX uk_sla_policy_ticket_type_type
    ON sla_policies (ticket_type, sla_type) WHERE ticket_type IS NOT NULL;