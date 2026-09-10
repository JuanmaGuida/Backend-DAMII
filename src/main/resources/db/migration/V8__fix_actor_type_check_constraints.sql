-- Revisión post-actualización de documentación (Entidades V1.49 §21 /
-- Eventos V1.69 §5.2): V1__create_initial_schema.sql dejó estos dos CHECK
-- constraints con sólo 4 valores (CITIZEN, AGENT, AREA_USER, SYSTEM),
-- desactualizados respecto al ActorType real de 6 valores (se agregan
-- AREA_RESPONSIBLE, ADMIN y EXTERNAL_USER; AREA_USER nunca fue un valor
-- válido de ActorType). No se edita V1 porque puede ya estar aplicada
-- sobre la base de datos de desarrollo.
ALTER TABLE ticket_messages
    DROP CONSTRAINT ck_ticket_message_author_type;

ALTER TABLE ticket_messages
    ADD CONSTRAINT ck_ticket_message_author_type CHECK (
        author_type IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN', 'EXTERNAL_USER', 'SYSTEM')
        );

ALTER TABLE ticket_activities
    DROP CONSTRAINT ck_ticket_activity_actor_type;

ALTER TABLE ticket_activities
    ADD CONSTRAINT ck_ticket_activity_actor_type CHECK (
        actor_type IN ('CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN', 'EXTERNAL_USER', 'SYSTEM')
        );
