INSERT INTO work_calendars (name, zone_id, workday_start, workday_end)
SELECT 'DEFAULT_BUSINESS_CALENDAR', 'America/Argentina/Buenos_Aires', TIME '08:00', TIME '16:00'
    WHERE NOT EXISTS (SELECT 1 FROM work_calendars WHERE name = 'DEFAULT_BUSINESS_CALENDAR');

INSERT INTO work_calendar_working_days (work_calendar_id, day_of_week)
SELECT calendar.id, day_name
FROM work_calendars calendar
         CROSS JOIN (VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')) days(day_name)
WHERE calendar.name = 'DEFAULT_BUSINESS_CALENDAR'
    ON CONFLICT DO NOTHING;

INSERT INTO sla_policies
(priority, ticket_type, sla_type, deadline_rule, mode, duration_seconds,
 duration_business_days, work_calendar_id)
SELECT policy_values.priority, NULL, policy_values.sla_type, policy_values.deadline_rule, policy_values.mode,
       policy_values.duration_seconds, policy_values.duration_business_days,
       CASE WHEN policy_values.mode = 'BUSINESS_HOURS' THEN calendar.id ELSE NULL END
FROM (VALUES
          ('CRITICAL', 'FIRST_RESPONSE', 'HOURS',        'CONTINUOUS_24X7',  7200::BIGINT, NULL::INTEGER),
          ('CRITICAL', 'RESOLUTION',     'HOURS',        'CONTINUOUS_24X7', 86400::BIGINT, NULL::INTEGER),
          ('HIGH',     'FIRST_RESPONSE', 'HOURS',        'BUSINESS_HOURS',  14400::BIGINT, NULL::INTEGER),
          ('HIGH',     'RESOLUTION',     'HOURS',        'BUSINESS_HOURS', 259200::BIGINT, NULL::INTEGER),
          ('MEDIUM',   'FIRST_RESPONSE', 'HOURS',        'BUSINESS_HOURS',  28800::BIGINT, NULL::INTEGER),
          ('MEDIUM',   'RESOLUTION',     'BUSINESS_DAYS','BUSINESS_HOURS',  NULL::BIGINT, 5),
          ('LOW',      'FIRST_RESPONSE', 'HOURS',        'BUSINESS_HOURS',  86400::BIGINT, NULL::INTEGER),
          ('LOW',      'RESOLUTION',     'BUSINESS_DAYS','BUSINESS_HOURS',  NULL::BIGINT, 10)
     ) policy_values(priority, sla_type, deadline_rule, mode, duration_seconds, duration_business_days)
         CROSS JOIN (SELECT id FROM work_calendars WHERE name = 'DEFAULT_BUSINESS_CALENDAR') calendar
    ON CONFLICT (priority, sla_type) WHERE priority IS NOT NULL DO UPDATE SET
    deadline_rule = EXCLUDED.deadline_rule,
    mode = EXCLUDED.mode,
    duration_seconds = EXCLUDED.duration_seconds,
    duration_business_days = EXCLUDED.duration_business_days,
    work_calendar_id = EXCLUDED.work_calendar_id;

INSERT INTO sla_policies
(priority, ticket_type, sla_type, deadline_rule, mode, duration_seconds,
 duration_business_days, work_calendar_id)
SELECT NULL, policy_values.ticket_type, 'RESOLUTION', policy_values.deadline_rule, 'BUSINESS_HOURS',
       NULL, policy_values.duration_business_days, calendar.id
FROM (VALUES
          ('INQUIRY',    'SAME_BUSINESS_DAY', NULL::INTEGER),
          ('SUGGESTION', 'BUSINESS_DAYS',     15)
     ) policy_values(ticket_type, deadline_rule, duration_business_days)
         CROSS JOIN (SELECT id FROM work_calendars WHERE name = 'DEFAULT_BUSINESS_CALENDAR') calendar
    ON CONFLICT (ticket_type, sla_type) WHERE ticket_type IS NOT NULL DO UPDATE SET
    deadline_rule = EXCLUDED.deadline_rule,
    mode = EXCLUDED.mode,
    duration_seconds = EXCLUDED.duration_seconds,
    duration_business_days = EXCLUDED.duration_business_days,
    work_calendar_id = EXCLUDED.work_calendar_id;

ALTER TABLE sla_policies ADD CONSTRAINT ck_sla_policy_critical_commitment CHECK (
    priority <> 'CRITICAL'
        OR (deadline_rule = 'HOURS' AND mode = 'CONTINUOUS_24X7'
        AND duration_seconds = CASE sla_type
                                   WHEN 'FIRST_RESPONSE' THEN 7200
                                   WHEN 'RESOLUTION' THEN 86400
            END)
    );