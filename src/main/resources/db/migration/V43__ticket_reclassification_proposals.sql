ALTER TABLE information_requests ADD COLUMN purpose VARCHAR(30) NOT NULL DEFAULT 'GENERAL';
ALTER TABLE information_requests ADD CONSTRAINT ck_information_request_purpose
    CHECK (purpose IN ('GENERAL','RECLASSIFICATION'));

CREATE TABLE ticket_reclassification_proposals (
    id BIGSERIAL PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES tickets(id),
    origin VARCHAR(30) NOT NULL CHECK (origin IN ('INITIAL_REVIEW','REQUEST_TYPE_MISMATCH')),
    status VARCHAR(30) NOT NULL CHECK (status IN ('PROPOSED','WAITING_INFORMATION','READY','CONFIRMED','ABANDONED')),
    target_request_type_id BIGINT NOT NULL REFERENCES request_types(id),
    target_form_template_id BIGINT REFERENCES form_templates(id),
    proposed_data JSONB NOT NULL,
    previous_classification_snapshot JSONB NOT NULL,
    information_request_id UUID UNIQUE REFERENCES information_requests(id),
    authorizing_return_event_id UUID,
    proposed_by VARCHAR(100) NOT NULL,
    confirmed_by VARCHAR(100),
    abandonment_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_reclassification_resolution CHECK (
        (status IN ('CONFIRMED','ABANDONED')) = (resolved_at IS NOT NULL)),
    CONSTRAINT ck_reclassification_abandonment CHECK (
        status <> 'ABANDONED' OR length(trim(abandonment_reason)) > 0 AND abandonment_reason IS NOT NULL),
    CONSTRAINT ck_reclassification_confirmation CHECK (status <> 'CONFIRMED' OR confirmed_by IS NOT NULL),
    CONSTRAINT ck_reclassification_authorization CHECK (
        (origin = 'REQUEST_TYPE_MISMATCH') = (authorizing_return_event_id IS NOT NULL))
);
CREATE UNIQUE INDEX uk_reclassification_open ON ticket_reclassification_proposals(ticket_id)
    WHERE status IN ('PROPOSED','WAITING_INFORMATION','READY');
CREATE INDEX idx_reclassification_history ON ticket_reclassification_proposals(ticket_id,id);
ALTER TABLE attachments ADD COLUMN reclassification_proposal_id BIGINT
    REFERENCES ticket_reclassification_proposals(id);
CREATE INDEX idx_attachment_reclassification ON attachments(reclassification_proposal_id)
    WHERE reclassification_proposal_id IS NOT NULL;

-- Snapshots and resolved attempts are immutable even for accidental direct SQL updates.
CREATE FUNCTION protect_reclassification_history() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('CONFIRMED','ABANDONED') OR
       NEW.previous_classification_snapshot IS DISTINCT FROM OLD.previous_classification_snapshot OR
       NEW.ticket_id IS DISTINCT FROM OLD.ticket_id OR NEW.origin IS DISTINCT FROM OLD.origin OR
       NEW.target_request_type_id IS DISTINCT FROM OLD.target_request_type_id OR
       NEW.target_form_template_id IS DISTINCT FROM OLD.target_form_template_id OR
       NEW.authorizing_return_event_id IS DISTINCT FROM OLD.authorizing_return_event_id OR
       NEW.proposed_by IS DISTINCT FROM OLD.proposed_by OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Immutable reclassification history' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_reclassification_history BEFORE UPDATE ON ticket_reclassification_proposals
    FOR EACH ROW EXECUTE FUNCTION protect_reclassification_history();
