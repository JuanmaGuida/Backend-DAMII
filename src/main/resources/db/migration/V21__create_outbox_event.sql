-- Renumerada de V5 a V21 tras detectar la colisión de versiones Flyway
-- post-merge feature-nico -> dev (DDA2-160): V5-V10 ya estaban ocupadas de
-- forma independiente en dev con contenido distinto. Se reubica después de
-- V20, que era la última versión de dev al momento del merge. Contenido sin
-- cambios respecto al V5 original.
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    update_type VARCHAR(30),
    ticket_id UUID NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    published_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_outbox_event_event_id UNIQUE (event_id),
    CONSTRAINT fk_outbox_event_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id),
    CONSTRAINT ck_outbox_event_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_event_type CHECK (event_type IN ('ticketCreated', 'ticketUpdated'))
);

CREATE INDEX idx_outbox_event_status_created ON outbox_events (status, created_at);
