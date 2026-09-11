CREATE TABLE ticket_resolutions (
                                    id UUID PRIMARY KEY,
                                    ticket_id UUID NOT NULL,
                                    resolution_type VARCHAR(50) NOT NULL,
                                    public_message TEXT NOT NULL,
                                    internal_message TEXT,
                                    resolved_by_type VARCHAR(30) NOT NULL,
                                    resolved_by_id VARCHAR(100) NOT NULL,
                                    resolved_by_module_id VARCHAR(20) NOT NULL,
                                    resolved_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                    CONSTRAINT fk_ticket_resolution_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id),
                                    CONSTRAINT ck_ticket_resolution_type CHECK (resolution_type IN (
                                                                                                    'ACTION_COMPLETED', 'REQUEST_FULFILLED', 'INQUIRY_ANSWERED', 'ACKNOWLEDGED',
                                                                                                    'NO_FURTHER_ACTION_REQUIRED'
                                        )),
                                    CONSTRAINT ck_ticket_resolution_actor CHECK (resolved_by_type IN (
                                                                                                      'CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN', 'EXTERNAL_USER', 'SYSTEM'
                                        ))
);

CREATE INDEX idx_ticket_resolution_ticket_resolved_at
    ON ticket_resolutions (ticket_id, resolved_at);