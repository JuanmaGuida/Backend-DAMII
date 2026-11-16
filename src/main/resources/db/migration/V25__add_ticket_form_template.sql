-- Renumerada de V10 a V25 tras el merge feature-nico -> dev. La columna y
-- una FK compuesta más estricta ya existen desde V7 en la secuencia unificada;
-- sólo faltaba conservar el índice que aportaba la migración original.

CREATE INDEX idx_ticket_form_template
    ON tickets (form_template_id);
