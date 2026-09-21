CREATE TABLE notification_logs (
                                   id BIGSERIAL PRIMARY KEY,
                                   ticket_id UUID NOT NULL,
                                   citizen_id UUID,
                                   type VARCHAR(50) NOT NULL,
                                   channel VARCHAR(30) NOT NULL,
                                   status VARCHAR(20) NOT NULL,
                                   attempt_count INTEGER NOT NULL DEFAULT 0,
                                   sent_at TIMESTAMP WITH TIME ZONE,
                                   failed_at TIMESTAMP WITH TIME ZONE,
                                   failure_reason VARCHAR(255),

                                   CONSTRAINT fk_notification_log_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
                                   CONSTRAINT ck_notification_log_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
                                   CONSTRAINT ck_notification_log_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX idx_notification_log_ticket ON notification_logs(ticket_id);
CREATE INDEX idx_notification_log_status ON notification_logs(status);