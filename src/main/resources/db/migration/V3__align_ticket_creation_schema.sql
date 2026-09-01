-- request_types
ALTER TABLE request_types DROP CONSTRAINT ck_request_type_initial_priority;
ALTER TABLE request_types DROP CONSTRAINT ck_request_type_initial_risk;
ALTER TABLE request_types RENAME COLUMN initial_priority TO minimum_priority;
ALTER TABLE request_types RENAME COLUMN initial_risk TO base_risk;
UPDATE request_types SET base_risk = 'LOW' WHERE base_risk = 'VERY_LOW';
ALTER TABLE request_types ADD COLUMN affected_population_factor NUMERIC(5,4) NOT NULL DEFAULT 0;
ALTER TABLE request_types ADD CONSTRAINT ck_request_type_minimum_priority CHECK (
    minimum_priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    );
ALTER TABLE request_types ADD CONSTRAINT ck_request_type_base_risk CHECK (
    base_risk IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    );
ALTER TABLE request_types ADD CONSTRAINT ck_request_type_affected_population_factor CHECK (
    affected_population_factor >= 0 AND affected_population_factor <= 1
    );

-- tickets
ALTER TABLE tickets DROP CONSTRAINT ck_ticket_anonymous_citizen;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM tickets WHERE citizen_id IS NOT NULL) THEN
        RAISE EXCEPTION
            'No se puede migrar tickets.citizen_id a UUID: existen valores BIGINT que requieren mapeo real desde M1 o un reset de datos';
END IF;
END $$;
ALTER TABLE tickets ALTER COLUMN citizen_id TYPE UUID USING NULL::UUID;
ALTER TABLE tickets ADD CONSTRAINT ck_ticket_anonymous_citizen CHECK (
    (is_anonymous = TRUE AND citizen_id IS NULL)
        OR (is_anonymous = FALSE AND citizen_id IS NOT NULL)
    );
ALTER TABLE tickets DROP CONSTRAINT ck_ticket_initial_priority;
ALTER TABLE tickets DROP COLUMN initial_priority;
ALTER TABLE tickets DROP COLUMN ticket_version;
ALTER TABLE tickets RENAME COLUMN affected_count TO estimated_affected_count;
ALTER TABLE tickets ADD COLUMN classification_finalized_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE tickets ADD COLUMN current_progress SMALLINT;
ALTER TABLE tickets ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tickets ADD CONSTRAINT ck_ticket_current_progress CHECK (
    current_progress IS NULL OR current_progress BETWEEN 0 AND 100
    );
ALTER TABLE tickets ADD CONSTRAINT ck_ticket_main_not_self CHECK (
    main_ticket_id IS NULL OR main_ticket_id <> id
    );
ALTER TABLE tickets ADD CONSTRAINT uk_ticket_tracking_code_hash UNIQUE (tracking_code_hash);

-- neighborhood
ALTER TABLE neighborhood ALTER COLUMN name TYPE VARCHAR(150);
ALTER TABLE neighborhood ADD CONSTRAINT ck_neighborhood_population CHECK (population >= 0);

-- ticket_locations
ALTER TABLE ticket_locations DROP COLUMN postal_code;
ALTER TABLE ticket_locations ADD COLUMN reference VARCHAR(500);

-- ticket_activities (ticket_version intentionally remains: TicketActivity still maps it)
ALTER TABLE ticket_activities ADD COLUMN previous_priority VARCHAR(20);
ALTER TABLE ticket_activities ADD COLUMN new_priority VARCHAR(20);
ALTER TABLE ticket_activities ADD CONSTRAINT ck_ticket_activity_previous_priority CHECK (
    previous_priority IS NULL OR previous_priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    );
ALTER TABLE ticket_activities ADD CONSTRAINT ck_ticket_activity_new_priority CHECK (
    new_priority IS NULL OR new_priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
    );
ALTER TABLE ticket_activities DROP CONSTRAINT ck_ticket_activity_action_type;
ALTER TABLE ticket_activities ADD CONSTRAINT ck_ticket_activity_action_type CHECK (
    action_type IN (
                    'TICKET_CREATED', 'REVIEW_STARTED', 'STATE_CHANGED', 'REQUEST_TYPE_CHANGED', 'ROUTED',
                    'RETURNED_BY_AREA', 'PRIORITY_CHANGED', 'ESCALATED', 'INFORMATION_REQUIRED',
                    'INFORMATION_PROVIDED', 'PROGRESS_REPORTED', 'DUPLICATE_LINKED', 'RESOLVED',
                    'REOPENED', 'CANCELLATION_REQUESTED', 'CANCELLATION_APPROVED',
                    'CANCELLATION_REJECTED', 'CANCELLED', 'CLOSED', 'PUBLIC_MESSAGE_SENT',
                    'INTERNAL_MESSAGE_ADDED', 'ATTACHMENT_ADDED'
        )
    );
ALTER TABLE ticket_activities DROP CONSTRAINT ck_ticket_activity_actor_type;
ALTER TABLE ticket_activities ADD CONSTRAINT ck_ticket_activity_actor_type CHECK (
    actor_type IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN')
    );

-- ticket_messages
ALTER TABLE ticket_messages DROP CONSTRAINT ck_ticket_message_author_type;
ALTER TABLE ticket_messages ADD CONSTRAINT ck_ticket_message_author_type CHECK (
    author_type IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN')
    );

-- form_templates
CREATE UNIQUE INDEX uk_form_template_active_request_type
    ON form_templates (request_type_id)
    WHERE active = TRUE;