-- The previous provider only issued synthetic citizen identities, including staff.
-- Keep their UUIDs, internal IDs, roles and every historical reference unchanged.
ALTER TABLE module_users
    ADD COLUMN actor_id UUID,
    ADD COLUMN actor_type VARCHAR(20),
    ADD COLUMN identity_source VARCHAR(20);

UPDATE module_users
SET actor_id = citizen_id, actor_type = 'CIUDADANO', identity_source = 'MOCK';

ALTER TABLE module_users
    ALTER COLUMN actor_id SET NOT NULL,
    ALTER COLUMN actor_type SET NOT NULL,
    ALTER COLUMN identity_source SET NOT NULL,
    ALTER COLUMN citizen_id DROP NOT NULL,
    ADD CONSTRAINT uk_module_user_actor_id UNIQUE (actor_id),
    ADD CONSTRAINT ck_module_user_identity_source CHECK (identity_source IN ('MOCK', 'M1')),
    ADD CONSTRAINT ck_module_user_actor_identity CHECK (
        (actor_type = 'CIUDADANO' AND citizen_id IS NOT NULL AND actor_id = citizen_id)
        OR (actor_type = 'EMPLEADO' AND citizen_id IS NULL AND role IN ('AGENT', 'AREA_RESPONSIBLE', 'ADMIN'))
    );

-- A staff recipient may have no citizen identity. Preserve the old recipient field.
ALTER TABLE notification_logs ADD COLUMN recipient_actor_id UUID;
UPDATE notification_logs SET recipient_actor_id = citizen_id WHERE citizen_id IS NOT NULL;
