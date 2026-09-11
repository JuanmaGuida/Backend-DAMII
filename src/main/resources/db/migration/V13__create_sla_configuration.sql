ALTER TABLE tickets ADD COLUMN resolution_due_at TIMESTAMP WITH TIME ZONE;

CREATE TABLE work_calendars (
                                id BIGSERIAL PRIMARY KEY, name VARCHAR(100) NOT NULL, zone_id VARCHAR(100) NOT NULL,
                                workday_start TIME NOT NULL, workday_end TIME NOT NULL,
                                CONSTRAINT uk_work_calendar_name UNIQUE (name),
                                CONSTRAINT ck_work_calendar_hours CHECK (workday_start < workday_end)
);
CREATE TABLE work_calendar_working_days (
                                            work_calendar_id BIGINT NOT NULL REFERENCES work_calendars(id) ON DELETE CASCADE,
                                            day_of_week VARCHAR(10) NOT NULL, PRIMARY KEY (work_calendar_id, day_of_week),
                                            CONSTRAINT ck_work_calendar_day CHECK (day_of_week IN ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY'))
);
CREATE TABLE work_calendar_non_working_days (
                                                work_calendar_id BIGINT NOT NULL REFERENCES work_calendars(id) ON DELETE CASCADE,
                                                non_working_date DATE NOT NULL, PRIMARY KEY (work_calendar_id, non_working_date)
);
CREATE TABLE sla_policies (
                              id BIGSERIAL PRIMARY KEY, priority VARCHAR(20) NOT NULL, mode VARCHAR(30) NOT NULL,
                              duration_seconds BIGINT NOT NULL, work_calendar_id BIGINT,
                              reset_on_routing BOOLEAN NOT NULL,
                              CONSTRAINT uk_sla_policy_priority UNIQUE (priority),
                              CONSTRAINT ck_sla_policy_priority CHECK (priority IN ('LOW','MEDIUM','HIGH','CRITICAL')),
                              CONSTRAINT ck_sla_policy_mode CHECK (mode IN ('BUSINESS_HOURS','CONTINUOUS_24X7')),
                              CONSTRAINT ck_sla_policy_duration CHECK (duration_seconds > 0),
                              CONSTRAINT ck_sla_policy_calendar CHECK ((mode = 'BUSINESS_HOURS' AND work_calendar_id IS NOT NULL) OR (mode = 'CONTINUOUS_24X7' AND work_calendar_id IS NULL)),
                              CONSTRAINT ck_sla_policy_critical_mode CHECK ((priority = 'CRITICAL' AND mode = 'CONTINUOUS_24X7') OR (priority <> 'CRITICAL' AND mode = 'BUSINESS_HOURS')),
                              CONSTRAINT fk_sla_policy_work_calendar FOREIGN KEY (work_calendar_id) REFERENCES work_calendars(id)
);
CREATE INDEX idx_ticket_resolution_due_at ON tickets (resolution_due_at) WHERE resolution_due_at IS NOT NULL;