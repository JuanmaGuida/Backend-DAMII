ALTER TABLE sla_policies DROP CONSTRAINT ck_sla_policy_critical_mode;

ALTER TABLE sla_policies ADD CONSTRAINT ck_sla_policy_critical_mode CHECK (
    priority <> 'CRITICAL' OR mode = 'CONTINUOUS_24X7'
    );