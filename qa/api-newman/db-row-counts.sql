SELECT json_build_object(
  'tickets', (SELECT count(*) FROM tickets),
  'ticket_activities', (SELECT count(*) FROM ticket_activities),
  'outbox_events', (SELECT count(*) FROM outbox_events),
  'attachments', (SELECT count(*) FROM attachments)
)::text;
