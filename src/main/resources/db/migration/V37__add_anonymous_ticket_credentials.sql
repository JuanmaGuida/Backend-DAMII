ALTER TABLE tickets
    ADD COLUMN anonymous_access_password_hash VARCHAR(255),
    ADD COLUMN anonymous_contact_channel VARCHAR(20),
    ADD COLUMN anonymous_contact_value VARCHAR(320);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM tickets WHERE is_anonymous = TRUE) THEN
        RAISE EXCEPTION
            'V37 no puede agregar credenciales a tickets anónimos existentes sin inventar una contraseña';
    END IF;
END $$;

ALTER TABLE tickets DROP CONSTRAINT ck_ticket_anonymous_citizen;

ALTER TABLE tickets ADD CONSTRAINT ck_ticket_anonymous_identity CHECK (
    (
        is_anonymous = TRUE
        AND citizen_id IS NULL
        AND anonymous_access_password_hash IS NOT NULL
    )
    OR
    (
        is_anonymous = FALSE
        AND citizen_id IS NOT NULL
        AND anonymous_access_password_hash IS NULL
        AND anonymous_contact_channel IS NULL
        AND anonymous_contact_value IS NULL
    )
);

ALTER TABLE tickets ADD CONSTRAINT ck_ticket_anonymous_contact_pair CHECK (
    (anonymous_contact_channel IS NULL AND anonymous_contact_value IS NULL)
    OR
    (anonymous_contact_channel IS NOT NULL AND anonymous_contact_value IS NOT NULL)
);

ALTER TABLE tickets ADD CONSTRAINT ck_ticket_anonymous_contact_owner CHECK (
    anonymous_contact_channel IS NULL OR is_anonymous = TRUE
);

ALTER TABLE tickets ADD CONSTRAINT ck_ticket_anonymous_contact_channel CHECK (
    anonymous_contact_channel IS NULL OR anonymous_contact_channel IN ('EMAIL', 'PHONE')
);

ALTER TABLE tickets ADD CONSTRAINT ck_ticket_anonymous_contact_value CHECK (
    anonymous_contact_value IS NULL OR LENGTH(BTRIM(anonymous_contact_value)) > 0
);
