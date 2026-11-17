CREATE TEMP TABLE qa_mass_assignment_sample AS
SELECT * FROM tickets WHERE summary = :'qa_ticket_summary';

DO $$
DECLARE
  sample_ticket tickets%ROWTYPE;
  sample_count INTEGER;
BEGIN
  SELECT count(*) INTO sample_count FROM qa_mass_assignment_sample;
  IF sample_count <> 1 THEN
    RAISE EXCEPTION 'Expected one isolated ticket fixture, found %', sample_count;
  END IF;

  SELECT * INTO sample_ticket FROM qa_mass_assignment_sample;
  IF sample_ticket.current_status <> 'REGISTERED' THEN
    RAISE EXCEPTION 'Unexpected persisted ticket status';
  END IF;
  IF sample_ticket.current_priority = 'CRITICAL' THEN
    RAISE EXCEPTION 'Forbidden currentPriority was persisted';
  END IF;
  IF sample_ticket.citizen_id <> '10000000-0000-0000-0000-000000000001'::uuid THEN
    RAISE EXCEPTION 'Authenticated synthetic citizen ownership changed';
  END IF;
  IF (SELECT role FROM module_users WHERE actor_id = '10000000-0000-0000-0000-000000000001'::uuid) <> 'CITIZEN' THEN
    RAISE EXCEPTION 'Authenticated synthetic citizen role changed';
  END IF;
  IF sample_ticket.responsible_area_id <> 'M6' THEN
    RAISE EXCEPTION 'Trusted request type area was changed';
  END IF;
  IF sample_ticket.form_data ?| ARRAY['riskScore', 'isPublic'] THEN
    RAISE EXCEPTION 'Forbidden root property leaked into form_data';
  END IF;
  IF (SELECT count(*) FROM outbox_events WHERE ticket_id = sample_ticket.id) <> 1 THEN
    RAISE EXCEPTION 'Expected one local outbox event for normal ticket creation';
  END IF;
  IF EXISTS (
    SELECT 1 FROM outbox_events
    WHERE ticket_id = sample_ticket.id
      AND payload -> 'data' -> 'formData' ?| ARRAY['riskScore', 'isPublic']
  ) THEN
    RAISE EXCEPTION 'Forbidden root property leaked into the outbox payload';
  END IF;
END $$;

SELECT json_build_object('status', 'PASS', 'case', 'synthetic ticket-create mass assignment',
                         'persistedStatus', 'REGISTERED', 'responsibleAreaId', 'M6')::text;
