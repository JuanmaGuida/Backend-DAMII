-- Revisión post-actualización de documentación (Eventos V1.69 §8.2): la
-- versión anterior de Eventos (v1.6) era ambigua sobre si RESOLVED podía
-- llegar directo desde ROUTED, y V7__add_request_type_direct_resolution.sql
-- modeló esa ambigüedad como un flag por RequestType. Eventos V1.69 aclara
-- que no existe tal configuración: M2 acepta RESOLVED de forma incondicional
-- tanto desde ROUTED como desde IN_PROGRESS. Se elimina la columna en lugar
-- de editar V7, que puede ya estar aplicada.
ALTER TABLE request_types
    DROP COLUMN allows_direct_resolution;
