ALTER TABLE ticket_cancellations ADD COLUMN cancellation_scope VARCHAR(20);
ALTER TABLE ticket_cancellations ADD CONSTRAINT ck_cancellation_scope
    CHECK (cancellation_scope IS NULL OR cancellation_scope IN ('GLOBAL','PRINCIPAL_ONLY'));

CREATE TABLE ticket_cancellation_requests (
    id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES tickets(id),
    requested_by_citizen_id UUID,
    requester_kind VARCHAR(20) NOT NULL CHECK (requester_kind IN ('IDENTIFIED','ANONYMOUS')),
    reason TEXT NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 4000),
    reason_code VARCHAR(50) NOT NULL CHECK (reason_code IN ('OUT_OF_SCOPE','DOES_NOT_APPLY',
        'INVALID_REQUEST_TYPE','INVALID_DATA','REJECTED_BY_AREA','WITHDRAWN_BY_CITIZEN',
        'DATA_ENTRY_ERROR','INFO_TIMEOUT','OTHER')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
    requested_at TIMESTAMPTZ NOT NULL,
    reviewed_at TIMESTAMPTZ,
    reviewed_by UUID,
    cancellation_scope VARCHAR(20) CHECK (cancellation_scope IN ('GLOBAL','PRINCIPAL_ONLY')),
    decision_reason TEXT,
    duplicate_snapshot JSONB NOT NULL CHECK (jsonb_typeof(duplicate_snapshot) = 'array'
        AND jsonb_array_length(duplicate_snapshot) > 0),
    requested_status_changed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_cancellation_request_identity CHECK (
        (requester_kind = 'IDENTIFIED') = (requested_by_citizen_id IS NOT NULL)),
    CONSTRAINT ck_cancellation_request_decision CHECK (
        (status = 'PENDING' AND reviewed_at IS NULL AND reviewed_by IS NULL
            AND cancellation_scope IS NULL AND decision_reason IS NULL) OR
        (status IN ('APPROVED','REJECTED') AND reviewed_at IS NOT NULL AND reviewed_at >= requested_at
            AND reviewed_by IS NOT NULL AND decision_reason IS NOT NULL
            AND length(trim(decision_reason)) BETWEEN 1 AND 4000
            AND ((status = 'APPROVED' AND cancellation_scope IS NOT NULL)
                 OR (status = 'REJECTED' AND cancellation_scope IS NULL))))
);
CREATE UNIQUE INDEX uk_cancellation_request_pending ON ticket_cancellation_requests(ticket_id)
    WHERE status = 'PENDING';
CREATE INDEX idx_cancellation_request_history ON ticket_cancellation_requests(ticket_id,requested_at,id);
CREATE INDEX idx_cancellation_request_inbox ON ticket_cancellation_requests(status,requested_at,id);

-- Automatic/external conflicts use existing private Activity history, not citizen requests.
CREATE UNIQUE INDEX uk_cancellation_external_conflict ON ticket_activities(ticket_id,external_event_id)
    WHERE reason_code='CANCELLATION_SCOPE_REQUIRED' AND external_event_id IS NOT NULL;
CREATE UNIQUE INDEX uk_cancellation_timeout_conflict ON ticket_activities(ticket_id,(metadata->>'informationRequestId'))
    WHERE reason_code='CANCELLATION_SCOPE_REQUIRED' AND metadata->>'informationRequestId' IS NOT NULL;
CREATE UNIQUE INDEX uk_cancellation_conflict_decision ON ticket_activities(ticket_id,(metadata->>'cancellationConflictId'))
    WHERE action_type IN ('CANCELLATION_APPROVED','CANCELLATION_REJECTED')
      AND metadata->>'cancellationConflictId' IS NOT NULL;

CREATE FUNCTION protect_cancellation_request_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status <> 'PENDING' OR NEW.ticket_id IS DISTINCT FROM OLD.ticket_id
        OR NEW.requested_by_citizen_id IS DISTINCT FROM OLD.requested_by_citizen_id
        OR NEW.requester_kind IS DISTINCT FROM OLD.requester_kind
        OR NEW.reason IS DISTINCT FROM OLD.reason OR NEW.reason_code IS DISTINCT FROM OLD.reason_code
        OR NEW.requested_at IS DISTINCT FROM OLD.requested_at
        OR NEW.duplicate_snapshot IS DISTINCT FROM OLD.duplicate_snapshot
        OR NEW.requested_status_changed_at IS DISTINCT FROM OLD.requested_status_changed_at THEN
        RAISE EXCEPTION 'Immutable cancellation request history' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_cancellation_request_history BEFORE UPDATE ON ticket_cancellation_requests
    FOR EACH ROW EXECUTE FUNCTION protect_cancellation_request_history();

-- Deferred because cancellation and review are persisted in the same transaction.
CREATE FUNCTION check_cancellation_request_scope() RETURNS trigger LANGUAGE plpgsql
    SET search_path FROM CURRENT AS $$
DECLARE ticket_key UUID;
BEGIN
    ticket_key := CASE WHEN TG_OP='DELETE' THEN OLD.ticket_id ELSE NEW.ticket_id END;
    IF EXISTS (SELECT 1 FROM ticket_cancellation_requests r LEFT JOIN ticket_cancellations c
        ON c.ticket_id=r.ticket_id WHERE r.ticket_id=ticket_key AND r.status='APPROVED'
        AND (c.id IS NULL OR c.cancellation_scope IS DISTINCT FROM r.cancellation_scope)) THEN
        RAISE EXCEPTION 'Cancellation decision scope mismatch' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER trg_request_cancellation_scope AFTER INSERT OR UPDATE ON ticket_cancellation_requests
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_cancellation_request_scope();
CREATE CONSTRAINT TRIGGER trg_terminal_cancellation_scope AFTER INSERT OR UPDATE OR DELETE ON ticket_cancellations
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_cancellation_request_scope();
