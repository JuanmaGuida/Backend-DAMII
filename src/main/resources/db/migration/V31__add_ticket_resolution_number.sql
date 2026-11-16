ALTER TABLE ticket_resolutions
    ADD COLUMN resolution_number INTEGER;

WITH numbered AS (
    SELECT id,
           (ROW_NUMBER() OVER (
               PARTITION BY ticket_id
               ORDER BY resolved_at, id
           ))::INTEGER AS resolution_number
    FROM ticket_resolutions
)
UPDATE ticket_resolutions resolution
SET resolution_number = numbered.resolution_number
FROM numbered
WHERE resolution.id = numbered.id;

ALTER TABLE ticket_resolutions
    ALTER COLUMN resolution_number SET NOT NULL;

ALTER TABLE ticket_resolutions
    ADD CONSTRAINT ck_ticket_resolution_number CHECK (resolution_number > 0);

ALTER TABLE ticket_resolutions
    ADD CONSTRAINT uk_ticket_resolution_number UNIQUE (ticket_id, resolution_number);
