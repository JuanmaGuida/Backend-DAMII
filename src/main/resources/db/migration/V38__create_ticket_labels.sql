CREATE TABLE labels (
                        id UUID PRIMARY KEY,
                        code VARCHAR(100) NOT NULL,
                        name VARCHAR(150) NOT NULL,
                        description TEXT,
                        active BOOLEAN NOT NULL DEFAULT TRUE,
                        created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                        CONSTRAINT uk_label_code UNIQUE (code)
);

CREATE TABLE ticket_labels (
                               ticket_id UUID NOT NULL,
                               label_id UUID NOT NULL,
                               source VARCHAR(10) NOT NULL,
                               created_by UUID NOT NULL,
                               created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                               CONSTRAINT pk_ticket_labels PRIMARY KEY (ticket_id, label_id),
                               CONSTRAINT fk_ticket_label_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id),
                               CONSTRAINT fk_ticket_label_label FOREIGN KEY (label_id) REFERENCES labels(id),
                               CONSTRAINT ck_ticket_label_source CHECK (source IN ('AUTO', 'MANUAL'))
);

CREATE INDEX idx_ticket_labels_label_id ON ticket_labels(label_id);