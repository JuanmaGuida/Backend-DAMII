-- Renumerada de V6 a V22 tras detectar la colisión de versiones Flyway
-- post-merge feature-nico -> dev (DDA2-160). Ver V21 para el detalle.
-- Contenido sin cambios respecto al V6 original.
CREATE TABLE inbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(30) NOT NULL,
    producer_module_id VARCHAR(20) NOT NULL,
    received_at TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    error TEXT,
    payload JSONB,
    CONSTRAINT ck_inbox_event_status CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED')),
    CONSTRAINT ck_inbox_event_type CHECK (event_type IN ('updateTicketStatus'))
);
