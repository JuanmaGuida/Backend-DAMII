-- Minimal pending INFO_TIMEOUT conflict fixture, based on the audit record used by CancellationConflictAudit.
-- It is loaded only in the job-local PostgreSQL database before the write-effect baseline.
WITH request_type AS (
  SELECT id, ticket_type, responsible_area_id FROM request_types WHERE active ORDER BY id LIMIT 1
), principal AS (
  INSERT INTO tickets (
    id, public_id, tracking_code_hash, is_anonymous, anonymous_access_password_hash,
    request_type_id, ticket_type, responsible_area_id, summary, description, form_data,
    current_status, current_priority, status_changed_at, created_at, updated_at
  )
  SELECT 'ebc00000-0000-4000-8000-000000000001', 'TK-2000-900001',
    'synthetic-conflict-parent-hash', true, 'synthetic-inactive-credential-hash',
    id, ticket_type, responsible_area_id, 'Fixture Newman de conflicto', 'Principal del conflicto sintético',
    '{}'::jsonb, 'IN_REVIEW', 'LOW', current_timestamp, current_timestamp, current_timestamp
  FROM request_type
  RETURNING id, request_type_id, ticket_type, responsible_area_id, status_changed_at
), duplicate AS (
  INSERT INTO tickets (
    id, public_id, tracking_code_hash, is_anonymous, anonymous_access_password_hash,
    request_type_id, ticket_type, responsible_area_id, summary, description, form_data,
    current_status, current_priority, status_changed_at, created_at, updated_at, main_ticket_id
  )
  SELECT 'ebc00000-0000-4000-8000-000000000002', 'TK-2000-900002',
    'synthetic-conflict-child-hash', true, 'synthetic-inactive-credential-hash',
    request_type_id, ticket_type, responsible_area_id, 'Duplicado del conflicto Newman', 'Duplicado sintético',
    '{}'::jsonb, 'DUPLICATE', 'LOW', current_timestamp, current_timestamp, current_timestamp, id
  FROM principal
  RETURNING id, main_ticket_id
)
INSERT INTO ticket_activities (
  ticket_id, sequence, action_type, previous_status, new_status, actor_type,
  source_module_id, reason_code, message, metadata, occurred_at, created_at
)
SELECT principal.id, 1, 'STATE_CHANGED', 'PENDING_INFORMATION', 'IN_REVIEW', 'SYSTEM', 'M2',
  'CANCELLATION_SCOPE_REQUIRED', 'INFO_TIMEOUT requiere revisión administrativa',
  jsonb_build_object('origin', 'INFO_TIMEOUT', 'reasonCode', 'INFO_TIMEOUT', 'expectedStatus', 'IN_REVIEW',
    'statusChangedAt', principal.status_changed_at, 'duplicateSnapshot', jsonb_build_array(duplicate.id)),
  current_timestamp, current_timestamp
FROM principal JOIN duplicate ON duplicate.main_ticket_id = principal.id;
