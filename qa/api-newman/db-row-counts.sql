SELECT json_build_object(
  'tickets', (SELECT count(*) FROM tickets),
  'created_ticket_events', (SELECT count(*) FROM outbox_events WHERE event_type = 'ticketCreated'),
  'owned_fixture', (SELECT count(*) FROM tickets WHERE summary = 'Reclamo identificado QA Newman'),
  'idempotency_rejected', (SELECT count(*) FROM tickets WHERE summary = 'Resumen modificado para verificar conflicto'),
  'ticket_activities', (SELECT count(*) FROM ticket_activities),
  'outbox_events', (SELECT count(*) FROM outbox_events),
  'attachments', (SELECT count(*) FROM attachments),
  'categories', (SELECT count(*) FROM categories),
  'subcategories', (SELECT count(*) FROM subcategories),
  'request_types', (SELECT count(*) FROM request_types),
  'labels', (SELECT count(*) FROM labels),
  'pilot_label', (SELECT count(*) FROM labels WHERE code = 'DDA2_NEWMAN_OK'),
  'rejected_labels', (SELECT count(*) FROM labels WHERE code IN ('DDA2_NEWMAN_FORBIDDEN', 'DDA2_NEWMAN_REJECTED'))
)::text;
