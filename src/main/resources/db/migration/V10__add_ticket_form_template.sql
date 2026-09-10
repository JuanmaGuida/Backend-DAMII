-- Entidades V1.49 §4/§6: Ticket.formTemplateId (BIGINT FK FormTemplate,
-- null) no existía en el esquema. Es obligatorio cuando el RequestType
-- vigente posee formulario (hoy, en este backend, todos lo tienen — ver
-- FormValidationService.resolveActiveTemplate) y queda fijo junto con
-- RequestType y formData en cuanto classificationFinalizedAt se setea.
-- Nullable porque los tickets creados antes de esta columna no tienen
-- valor y porque la documentación contempla RequestTypes sin formulario.
ALTER TABLE tickets
    ADD COLUMN form_template_id BIGINT;

ALTER TABLE tickets
    ADD CONSTRAINT fk_ticket_form_template
        FOREIGN KEY (form_template_id) REFERENCES form_templates (id);

CREATE INDEX idx_ticket_form_template
    ON tickets (form_template_id);
