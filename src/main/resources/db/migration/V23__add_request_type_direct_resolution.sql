-- Renumerada de V7 a V23 tras detectar la colisión de versiones Flyway
-- post-merge feature-nico -> dev (DDA2-160). Ver V21 para el detalle.
-- Contenido sin cambios respecto al V7 original.
--
-- QA (BE - Implementar transiciones a partir del consumo de eventos):
-- Eventos v1.6 §8.2 permite RESOLVED desde ROUTED cuando el RequestType
-- admite resolución directa. Default false para todos los RequestType
-- existentes: nadie pierde comportamiento actual, hay que habilitarlo
-- explícitamente por RequestType el día que se necesite.
ALTER TABLE request_types
    ADD COLUMN allows_direct_resolution BOOLEAN NOT NULL DEFAULT FALSE;
