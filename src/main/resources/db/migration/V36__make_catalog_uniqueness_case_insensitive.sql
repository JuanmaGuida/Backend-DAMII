-- QA (FAIL de "validaciones de datos y relaciones jerárquicas del
-- catálogo", segunda vuelta): el fix anterior (409 controlado en
-- DataIntegrityViolationException) resuelve el 500, pero no la unicidad en
-- sí. uk_category_name, uk_subcategory_category_name, uk_request_type_code
-- (V1) y uk_request_type_subcategory_name (V35) son todas UNIQUE
-- case-sensitive, mientras que CatalogAdminService usa exclusivamente
-- pre-checks existsBy...IgnoreCase. Bajo una carrera real, dos altas que
-- sólo difieren en mayúsculas ("Alumbrado" / "alumbrado") pasan el
-- pre-check las dos y la base las deja insertar a ambas porque difieren en
-- bytes — quedan dos filas que la aplicación considera duplicadas.
--
-- Postgres no admite UNIQUE de tabla sobre una expresión (LOWER(...)); la
-- única forma de unicidad case-insensitive es un índice único funcional.
-- Se reemplazan las cuatro constraints por su versión sobre LOWER(...), que
-- es exactamente el mismo criterio que ya usa el pre-check de la
-- aplicación.

DO $$
DECLARE
    duplicate_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO duplicate_count
    FROM (
        SELECT LOWER(name)
        FROM categories
        GROUP BY LOWER(name)
        HAVING COUNT(*) > 1
    ) duplicates;

    IF duplicate_count > 0 THEN
        RAISE EXCEPTION
            'V36 no puede migrar: existen % categoría(s) con el mismo nombre case-insensitive. Hay que resolver esos duplicados a mano antes de aplicar esta migración.',
            duplicate_count;
    END IF;
END $$;

DO $$
DECLARE
    duplicate_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO duplicate_count
    FROM (
        SELECT category_id, LOWER(name)
        FROM subcategories
        GROUP BY category_id, LOWER(name)
        HAVING COUNT(*) > 1
    ) duplicates;

    IF duplicate_count > 0 THEN
        RAISE EXCEPTION
            'V36 no puede migrar: existen % subcategoría(s) con el mismo nombre case-insensitive dentro de la misma Category. Hay que resolver esos duplicados a mano antes de aplicar esta migración.',
            duplicate_count;
    END IF;
END $$;

DO $$
DECLARE
    duplicate_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO duplicate_count
    FROM (
        SELECT LOWER(code)
        FROM request_types
        GROUP BY LOWER(code)
        HAVING COUNT(*) > 1
    ) duplicates;

    IF duplicate_count > 0 THEN
        RAISE EXCEPTION
            'V36 no puede migrar: existen % código(s) de Request Type repetidos case-insensitive. Hay que resolver esos duplicados a mano antes de aplicar esta migración.',
            duplicate_count;
    END IF;
END $$;

DO $$
DECLARE
    duplicate_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO duplicate_count
    FROM (
        SELECT subcategory_id, LOWER(name)
        FROM request_types
        GROUP BY subcategory_id, LOWER(name)
        HAVING COUNT(*) > 1
    ) duplicates;

    IF duplicate_count > 0 THEN
        RAISE EXCEPTION
            'V36 no puede migrar: existen % Request Type(s) con el mismo nombre case-insensitive dentro de la misma Subcategory. Hay que resolver esos duplicados a mano antes de aplicar esta migración.',
            duplicate_count;
    END IF;
END $$;

ALTER TABLE categories DROP CONSTRAINT uk_category_name;
CREATE UNIQUE INDEX uk_category_name_ci ON categories (LOWER(name));

ALTER TABLE subcategories DROP CONSTRAINT uk_subcategory_category_name;
CREATE UNIQUE INDEX uk_subcategory_category_name_ci ON subcategories (category_id, LOWER(name));

ALTER TABLE request_types DROP CONSTRAINT uk_request_type_code;
CREATE UNIQUE INDEX uk_request_type_code_ci ON request_types (LOWER(code));

ALTER TABLE request_types DROP CONSTRAINT uk_request_type_subcategory_name;
CREATE UNIQUE INDEX uk_request_type_subcategory_name_ci ON request_types (subcategory_id, LOWER(name));
