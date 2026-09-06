-- Normalize every technical module/area identifier to the canonical M1..M9 namespace.
-- Human-readable area names remain available in catalog descriptions and are not technical IDs.

DO $$
DECLARE
    invalid_value TEXT;
BEGIN
    SELECT responsible_area_id INTO invalid_value
    FROM request_types
    WHERE responsible_area_id NOT IN (
        'M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9',
        'Ciudadanos y Organizaciones', 'Call Center', 'Obras Públicas',
        'Habilitaciones y Control Comercial', 'Rentas',
        'Ambiente y Servicios Urbanos', 'Tránsito', 'Desarrollo Social'
    )
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V9 abortada: valor desconocido en request_types.responsible_area_id: %', invalid_value;
    END IF;

    SELECT responsible_area_id INTO invalid_value
    FROM tickets
    WHERE responsible_area_id NOT IN (
        'M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9',
        'Ciudadanos y Organizaciones', 'Call Center', 'Obras Públicas',
        'Habilitaciones y Control Comercial', 'Rentas',
        'Ambiente y Servicios Urbanos', 'Tránsito', 'Desarrollo Social'
    )
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V9 abortada: valor desconocido en tickets.responsible_area_id: %', invalid_value;
    END IF;

    SELECT source_module_id INTO invalid_value
    FROM ticket_activities
    WHERE source_module_id IS NOT NULL
      AND source_module_id NOT IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9')
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V9 abortada: valor desconocido en ticket_activities.source_module_id: %', invalid_value;
    END IF;

    SELECT source_module_id INTO invalid_value
    FROM ticket_messages
    WHERE source_module_id IS NOT NULL
      AND source_module_id NOT IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9')
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V9 abortada: valor desconocido en ticket_messages.source_module_id: %', invalid_value;
    END IF;

    SELECT source_module_id INTO invalid_value
    FROM attachments
    WHERE source_module_id IS NOT NULL
      AND source_module_id NOT IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9')
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V9 abortada: valor desconocido en attachments.source_module_id: %', invalid_value;
    END IF;

    SELECT requested_by_module_id INTO invalid_value
    FROM information_requests
    WHERE requested_by_module_id IS NOT NULL
      AND requested_by_module_id NOT IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9')
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V9 abortada: valor desconocido en information_requests.requested_by_module_id: %', invalid_value;
    END IF;

    SELECT cancelled_by_module_id INTO invalid_value
    FROM ticket_cancellations
    WHERE cancelled_by_module_id IS NOT NULL
      AND cancelled_by_module_id NOT IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9')
    LIMIT 1;
    IF invalid_value IS NOT NULL THEN
        RAISE EXCEPTION 'V9 abortada: valor desconocido en ticket_cancellations.cancelled_by_module_id: %', invalid_value;
    END IF;
END $$;

-- Approved RequestType exceptions take precedence over the legacy human-readable owner.
UPDATE request_types
SET responsible_area_id = 'M6'
WHERE code IN (
    'INFORMAR_UNA_CABLES_EXPUESTOS',
    'INFORMAR_UNA_COLUMNA_DANADA',
    'INFORMAR_UNA_LUMINARIA_APAGADA',
    'INFORMAR_UNA_LUMINARIA_INTERMITENTE',
    'SOLICITAR_NUEVA_ILUMINACION'
);

UPDATE request_types
SET responsible_area_id = 'M2'
WHERE code = 'RECLAMAR_POR_UNA_DERIVACION_INCORRECTA';

-- Ticket responsibility is a snapshot, so historical rows are migrated explicitly.
UPDATE tickets ticket
SET responsible_area_id = 'M6'
FROM request_types request_type
WHERE ticket.request_type_id = request_type.id
  AND request_type.code IN (
      'INFORMAR_UNA_CABLES_EXPUESTOS',
      'INFORMAR_UNA_COLUMNA_DANADA',
      'INFORMAR_UNA_LUMINARIA_APAGADA',
      'INFORMAR_UNA_LUMINARIA_INTERMITENTE',
      'SOLICITAR_NUEVA_ILUMINACION'
  );

UPDATE tickets ticket
SET responsible_area_id = 'M2'
FROM request_types request_type
WHERE ticket.request_type_id = request_type.id
  AND request_type.code = 'RECLAMAR_POR_UNA_DERIVACION_INCORRECTA';

UPDATE request_types
SET responsible_area_id = CASE responsible_area_id
    WHEN 'Ciudadanos y Organizaciones' THEN 'M1'
    WHEN 'Call Center' THEN 'M2'
    WHEN 'Obras Públicas' THEN 'M3'
    WHEN 'Habilitaciones y Control Comercial' THEN 'M4'
    WHEN 'Rentas' THEN 'M5'
    WHEN 'Ambiente y Servicios Urbanos' THEN 'M6'
    WHEN 'Tránsito' THEN 'M7'
    WHEN 'Desarrollo Social' THEN 'M8'
    ELSE responsible_area_id
END;

UPDATE tickets
SET responsible_area_id = CASE responsible_area_id
    WHEN 'Ciudadanos y Organizaciones' THEN 'M1'
    WHEN 'Call Center' THEN 'M2'
    WHEN 'Obras Públicas' THEN 'M3'
    WHEN 'Habilitaciones y Control Comercial' THEN 'M4'
    WHEN 'Rentas' THEN 'M5'
    WHEN 'Ambiente y Servicios Urbanos' THEN 'M6'
    WHEN 'Tránsito' THEN 'M7'
    WHEN 'Desarrollo Social' THEN 'M8'
    ELSE responsible_area_id
END;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM request_types
        WHERE responsible_area_id NOT IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9')
    ) OR EXISTS (
        SELECT 1 FROM tickets
        WHERE responsible_area_id NOT IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9')
    ) THEN
        RAISE EXCEPTION 'V9 abortada: quedaron responsible_area_id fuera del namespace M1..M9';
    END IF;
END $$;

ALTER TABLE request_types ADD CONSTRAINT ck_request_type_responsible_area_namespace
    CHECK (responsible_area_id IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9'));

ALTER TABLE tickets ADD CONSTRAINT ck_ticket_responsible_area_namespace
    CHECK (responsible_area_id IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9'));

ALTER TABLE ticket_activities ADD CONSTRAINT ck_ticket_activity_source_module_namespace
    CHECK (source_module_id IS NULL OR source_module_id IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9'));

ALTER TABLE ticket_messages ADD CONSTRAINT ck_ticket_message_source_module_namespace
    CHECK (source_module_id IS NULL OR source_module_id IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9'));

ALTER TABLE attachments ADD CONSTRAINT ck_attachment_source_module_namespace
    CHECK (source_module_id IS NULL OR source_module_id IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9'));

ALTER TABLE information_requests ADD CONSTRAINT ck_information_request_module_namespace
    CHECK (requested_by_module_id IS NULL OR requested_by_module_id IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9'));

ALTER TABLE ticket_cancellations ADD CONSTRAINT ck_ticket_cancellation_module_namespace
    CHECK (cancelled_by_module_id IS NULL OR cancelled_by_module_id IN ('M1', 'M2', 'M3', 'M4', 'M5', 'M6', 'M7', 'M8', 'M9'));
