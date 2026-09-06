-- Align persisted actor semantics with ActorType without conflating actor IDs and identity-provider subjects.

DO $$
DECLARE
    invalid_value TEXT;
BEGIN
    SELECT actor_type INTO invalid_value
    FROM ticket_activities
    WHERE actor_type NOT IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN', 'EXTERNAL_USER', 'SYSTEM')
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V10 abortada: ActorType desconocido en ticket_activities.actor_type: %', invalid_value;
    END IF;

    SELECT author_type INTO invalid_value
    FROM ticket_messages
    WHERE author_type NOT IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN', 'EXTERNAL_USER', 'SYSTEM')
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V10 abortada: ActorType desconocido en ticket_messages.author_type: %', invalid_value;
    END IF;

    SELECT uploaded_by_type INTO invalid_value
    FROM attachments
    WHERE uploaded_by_type NOT IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN', 'EXTERNAL_USER', 'SYSTEM')
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V10 abortada: ActorType desconocido en attachments.uploaded_by_type: %', invalid_value;
    END IF;

    SELECT requested_by_actor_type INTO invalid_value
    FROM information_requests
    WHERE requested_by_actor_type NOT IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN')
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V10 abortada: ActorType desconocido en information_requests.requested_by_actor_type: %', invalid_value;
    END IF;

    SELECT answered_by_type INTO invalid_value
    FROM information_requests
    WHERE answered_by_type IS NOT NULL
      AND answered_by_type NOT IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN')
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V10 abortada: ActorType desconocido en information_requests.answered_by_type: %', invalid_value;
    END IF;

    SELECT cancelled_by_type INTO invalid_value
    FROM ticket_cancellations
    WHERE cancelled_by_type NOT IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN', 'SYSTEM')
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V10 abortada: ActorType desconocido en ticket_cancellations.cancelled_by_type: %', invalid_value;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_requests
        WHERE requested_by_actor_type IN ('AGENT', 'ADMIN')
    ) OR EXISTS (
        SELECT 1
        FROM ticket_activities
        WHERE action_type = 'INFORMATION_REQUIRED'
          AND actor_type IN ('AGENT', 'ADMIN')
    ) THEN
        RAISE EXCEPTION
            'V10 no puede convertir de manera segura actor IDs históricos AGENT/ADMIN sin mapping subjectId->citizenId';
    END IF;
END $$;

ALTER TABLE ticket_activities DROP CONSTRAINT ck_ticket_activity_actor_type;
ALTER TABLE ticket_activities ADD CONSTRAINT ck_ticket_activity_actor_type CHECK (
    actor_type IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN', 'EXTERNAL_USER', 'SYSTEM')
);

ALTER TABLE ticket_messages DROP CONSTRAINT ck_ticket_message_author_type;
ALTER TABLE ticket_messages ADD CONSTRAINT ck_ticket_message_author_type CHECK (
    author_type IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN', 'EXTERNAL_USER', 'SYSTEM')
);

ALTER TABLE ticket_cancellations DROP CONSTRAINT ck_ticket_cancellation_actor;
ALTER TABLE ticket_cancellations ADD CONSTRAINT ck_ticket_cancellation_actor CHECK (
    cancelled_by_type IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN', 'SYSTEM')
);

UPDATE ticket_cancellations
SET cancelled_by_type = 'SYSTEM',
    cancelled_by_id = NULL
WHERE reason_code = 'INFO_TIMEOUT'
  AND cancelled_by_type = 'ADMIN'
  AND cancelled_by_id = 'system'
  AND cancelled_by_module_id = 'M2';

UPDATE ticket_activities activity
SET actor_type = 'SYSTEM',
    actor_id = NULL
FROM ticket_cancellations cancellation
WHERE activity.ticket_id = cancellation.ticket_id
  AND activity.action_type = 'CANCELLED'
  AND activity.reason_code = 'INFO_TIMEOUT'
  AND activity.actor_type = 'ADMIN'
  AND activity.actor_id = 'system'
  AND activity.source_module_id = 'M2'
  AND cancellation.reason_code = 'INFO_TIMEOUT'
  AND cancellation.cancelled_by_type = 'SYSTEM'
  AND cancellation.cancelled_by_id IS NULL
  AND cancellation.cancelled_by_module_id = 'M2';

UPDATE ticket_activities activity
SET actor_id = ticket.citizen_id::TEXT
FROM tickets ticket
WHERE activity.ticket_id = ticket.id
  AND activity.action_type = 'TICKET_CREATED'
  AND activity.actor_type = 'CITIZEN'
  AND ticket.citizen_id IS NOT NULL;

UPDATE information_requests information_request
SET answered_by_id = ticket.citizen_id::TEXT
FROM tickets ticket
WHERE information_request.ticket_id = ticket.id
  AND information_request.answered_by_type = 'CITIZEN'
  AND ticket.citizen_id IS NOT NULL;

UPDATE ticket_activities activity
SET actor_id = ticket.citizen_id::TEXT
FROM tickets ticket
WHERE activity.ticket_id = ticket.id
  AND activity.action_type = 'INFORMATION_PROVIDED'
  AND activity.actor_type = 'CITIZEN'
  AND ticket.citizen_id IS NOT NULL;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM ticket_cancellations
        WHERE reason_code = 'INFO_TIMEOUT'
          AND cancelled_by_type = 'ADMIN'
          AND cancelled_by_id = 'system'
          AND cancelled_by_module_id = 'M2'
    ) THEN
        RAISE EXCEPTION 'V10 abortada: quedaron cancelaciones INFO_TIMEOUT con ADMIN/system';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM ticket_activities activity
        JOIN tickets ticket ON ticket.id = activity.ticket_id
        WHERE activity.action_type = 'TICKET_CREATED'
          AND activity.actor_type = 'CITIZEN'
          AND ticket.citizen_id IS NOT NULL
          AND activity.actor_id IS DISTINCT FROM ticket.citizen_id::TEXT
    ) THEN
        RAISE EXCEPTION 'V10 abortada: quedaron actividades TICKET_CREATED identificadas sin citizenId';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_requests information_request
        JOIN tickets ticket ON ticket.id = information_request.ticket_id
        WHERE information_request.answered_by_type = 'CITIZEN'
          AND ticket.citizen_id IS NOT NULL
          AND information_request.answered_by_id IS DISTINCT FROM ticket.citizen_id::TEXT
    ) THEN
        RAISE EXCEPTION 'V10 abortada: quedaron respuestas identificadas sin citizenId';
    END IF;
END $$;
