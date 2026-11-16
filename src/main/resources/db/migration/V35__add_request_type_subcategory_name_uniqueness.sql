-- QA (FAIL de "validaciones de datos y relaciones jerárquicas del
-- catálogo"): a diferencia de categories (uk_category_name) y subcategories
-- (uk_subcategory_category_name), request_types nunca tuvo una constraint de
-- base que respalde el pre-check existsBySubcategory_IdAndNameIgnoreCase de
-- CatalogAdminService. Ese pre-check es TOCTOU-racy: bajo altas concurrentes
-- con el mismo nombre en la misma Subcategory, hoy pueden terminar
-- persistidos DOS Request Types con el mismo nombre sin que la base lo
-- impida — silencioso, sin siquiera el 500 que sí se ve con code (que sí
-- tiene uk_request_type_code). Se agrega la constraint simétrica a las otras
-- dos, con un guardrail que aborta con un mensaje claro si ya existen
-- duplicados en datos existentes en vez de fallar con el error genérico de
-- Postgres.
-- El chequeo es por igualdad exacta de texto porque eso es lo que la
-- constraint UNIQUE de más abajo va a exigir (mismo criterio ya usado por
-- uk_category_name/uk_subcategory_category_name: ninguna de las dos es
-- case-insensitive a nivel base tampoco, aunque el pre-check de
-- CatalogAdminService sí lo sea).
DO $$
DECLARE
    duplicate_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO duplicate_count
    FROM (
        SELECT subcategory_id, name
        FROM request_types
        GROUP BY subcategory_id, name
        HAVING COUNT(*) > 1
    ) duplicates;

    IF duplicate_count > 0 THEN
        RAISE EXCEPTION
            'V35 no puede migrar: existen % grupo(s) de Request Type con el mismo nombre exacto en la misma Subcategory. Hay que resolver esos duplicados a mano antes de aplicar esta migración.',
            duplicate_count;
    END IF;
END $$;

ALTER TABLE request_types
    ADD CONSTRAINT uk_request_type_subcategory_name UNIQUE (subcategory_id, name);
