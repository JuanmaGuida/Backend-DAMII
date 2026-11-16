CREATE TABLE information_requests (
                                      id UUID PRIMARY KEY,
                                      ticket_id UUID NOT NULL,
                                      requested_by_module_id VARCHAR(20),
                                      requested_by_actor_type VARCHAR(30) NOT NULL,
                                      requested_by_actor_id VARCHAR(100),
                                      message_for_citizen TEXT NOT NULL,
                                      internal_message TEXT,
                                      resume_status VARCHAR(30) NOT NULL,
                                      status VARCHAR(20) NOT NULL,
                                      requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                      due_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                      response_message TEXT,
                                      answered_by_type VARCHAR(30),
                                      answered_by_id VARCHAR(100),
                                      answered_at TIMESTAMP WITH TIME ZONE,
                                      CONSTRAINT fk_information_request_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id),
                                      CONSTRAINT ck_information_request_status CHECK (status IN ('PENDING', 'ANSWERED', 'EXPIRED')),
                                      CONSTRAINT ck_information_request_resume_status CHECK (
                                          resume_status IN ('IN_REVIEW', 'ROUTED', 'IN_PROGRESS')
                                          ),
                                      CONSTRAINT ck_information_request_request_actor CHECK (
                                          requested_by_actor_type IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN')
                                          ),
                                      CONSTRAINT ck_information_request_answer_actor CHECK (
                                          answered_by_type IS NULL OR answered_by_type IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN')
                                          ),
                                      CONSTRAINT ck_information_request_due CHECK (due_at > requested_at),
                                      CONSTRAINT ck_information_request_answer_fields CHECK (
                                          (status = 'ANSWERED' AND response_message IS NOT NULL AND answered_by_type IS NOT NULL
                                              AND answered_by_id IS NOT NULL AND answered_at IS NOT NULL)
                                              OR (status <> 'ANSWERED' AND response_message IS NULL AND answered_by_type IS NULL
                                              AND answered_by_id IS NULL AND answered_at IS NULL)
                                          )
);

CREATE INDEX idx_information_request_ticket ON information_requests (ticket_id);
CREATE INDEX idx_information_request_pending_due ON information_requests (status, due_at);
CREATE UNIQUE INDEX uk_information_request_pending_ticket
    ON information_requests (ticket_id) WHERE status = 'PENDING';

CREATE TABLE ticket_cancellations (
                                      id UUID PRIMARY KEY,
                                      ticket_id UUID NOT NULL,
                                      reason_code VARCHAR(50) NOT NULL,
                                      public_message TEXT,
                                      internal_message TEXT,
                                      cancelled_by_type VARCHAR(30) NOT NULL,
                                      cancelled_by_id VARCHAR(100),
                                      cancelled_by_module_id VARCHAR(20),
                                      cancelled_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                      CONSTRAINT uk_ticket_cancellation_ticket UNIQUE (ticket_id),
                                      CONSTRAINT fk_ticket_cancellation_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id),
                                      CONSTRAINT ck_ticket_cancellation_reason CHECK (reason_code IN ('INFO_TIMEOUT')),
                                      CONSTRAINT ck_ticket_cancellation_actor CHECK (
                                          cancelled_by_type IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN')
                                          )
);