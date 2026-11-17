CREATE TEMP TABLE qa_bfla_sample AS
SELECT * FROM tickets WHERE summary LIKE 'DDA2-QA-BFLA-%-' || :'qa_run_tag';

DO $$
BEGIN
  IF (SELECT count(*) FROM qa_bfla_sample) <> 4 THEN
    RAISE EXCEPTION 'Expected four isolated BFLA ticket fixtures';
  END IF;
  IF (SELECT count(*) FROM qa_bfla_sample WHERE responsible_area_id = 'M9') <> 1
     OR (SELECT count(*) FROM qa_bfla_sample WHERE responsible_area_id = 'M6') <> 3 THEN
    RAISE EXCEPTION 'Synthetic area fixtures do not match M6/M9 setup';
  END IF;
  IF EXISTS (SELECT 1 FROM qa_bfla_sample WHERE current_status <> 'REGISTERED') THEN
    RAISE EXCEPTION 'Denied lifecycle request changed a ticket status';
  END IF;
  IF EXISTS (
    SELECT 1 FROM qa_bfla_sample t
    WHERE (SELECT count(*) FROM outbox_events e WHERE e.ticket_id = t.id) <> 1
       OR (SELECT count(*) FROM ticket_activities a WHERE a.ticket_id = t.id) <> 1
       OR (SELECT count(*) FROM ticket_sla s WHERE s.ticket_id = t.id) <> 2
       OR (SELECT count(*) FROM notification_logs n WHERE n.ticket_id = t.id) <> 1
       OR (SELECT count(*) FROM attachments a WHERE a.ticket_id = t.id) <> 0
  ) THEN
    RAISE EXCEPTION 'Denied ownership/lifecycle probe produced an extra persistent side effect';
  END IF;
END $$;

SELECT json_build_object('status', 'PASS', 'case', 'synthetic BFLA ownership/lifecycle effects',
                         'ticketFixtures', 4, 'outboxPerTicket', 1, 'activitiesPerTicket', 1,
                         'slaRowsPerTicket', 2, 'notificationRowsPerTicket', 1,
                         'attachmentsPerTicket', 0)::text;
