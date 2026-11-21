ALTER TABLE external_attachment_references
    ADD COLUMN local_attachment_id BIGINT REFERENCES attachments(id) ON DELETE SET NULL;

CREATE INDEX idx_external_attachment_local_copy
    ON external_attachment_references(local_attachment_id) WHERE local_attachment_id IS NOT NULL;
