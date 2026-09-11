-- ================================================================
-- V6 - Actualización del catálogo Help Center a la versión vigente
-- Fuente: "DA2 - Help Center Catalog(1).xlsx" - hoja "Catálogo"
--
-- Catálogo objetivo:
--   14 categorías
--   52 subcategorías
--   110 Request Types
--
-- Diferencias respecto de V4:
--   108 Request Types se conservan
--   31 Request Types dejan de formar parte del catálogo vigente
--   2 Request Types nuevos
--
-- Esta migración actualiza Category, Subcategory y RequestType.
-- Además, crea FormTemplate y FormField para los 2 Request Types nuevos.
-- Los Request Types retirados se desactivan y, si no tienen tickets asociados,
-- se eliminan físicamente junto con sus formularios para que una base limpia
-- finalice con exactamente 110 Request Types.
--
-- Nota sobre "Active":
-- La planilla fuente tiene "No" en la columna Active para las 110 filas.
-- No se replica literalmente porque active es el flag operativo del backend
-- y hacerlo dejaría el catálogo inaccesible. Los Request Types presentes
-- en la hoja vigente quedan active=TRUE y los retirados de V4 quedan FALSE.
-- ================================================================

-- ================================================================
-- CATEGORIES (14)
-- ================================================================
INSERT INTO categories (name, description, active, created_at, updated_at)
VALUES
    ('Calles, veredas e infraestructura urbana', 'Hacé clic aquí para informar baches, calles o veredas dañadas, desagües tapados u otros problemas de infraestructura urbana.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Alumbrado y equipamiento urbano', 'Hacé clic aquí para informar luminarias apagadas, columnas dañadas, cables expuestos o problemas con el mobiliario urbano.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Limpieza, residuos y servicios urbanos', 'Hacé clic aquí para informar falta de recolección, contenedores desbordados, residuos acumulados o solicitar servicios de limpieza.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Arbolado, plazas y espacios verdes', 'Hacé clic aquí para informar árboles o ramas peligrosas, solicitar poda o reportar problemas en plazas y parques.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Ambiente y convivencia urbana', 'Hacé clic aquí para denunciar ruidos molestos, contaminación, vertidos, malos olores o situaciones que afecten la convivencia.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Comercios, habilitaciones e inspecciones', 'Hacé clic aquí para consultar habilitaciones, informar problemas con inspecciones o denunciar posibles irregularidades comerciales.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tránsito y seguridad vial', 'Hacé clic aquí para contactarnos por semáforos, señalización, estacionamiento, circulación o situaciones de riesgo vial.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Infracciones y vehículos retenidos', 'Hacé clic aquí para consultar infracciones, descargos, vehículos retenidos o problemas relacionados con su liberación.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Tasas, tributos y pagos municipales', 'Hacé clic aquí para consultar boletas, deudas, pagos, planes de pago, exenciones o informar errores de liquidación.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Desarrollo social y asistencia comunitaria', 'Hacé clic aquí para solicitar asistencia social, consultar beneficios o informar una situación de vulnerabilidad.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Salud comunitaria y actividades municipales', 'Hacé clic aquí para consultar turnos, campañas, talleres, centros municipales o actividades comunitarias.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Datos ciudadanos, organizaciones y acceso', 'Hacé clic aquí para actualizar datos personales, informar errores en una cuenta o consultar registros de ciudadanos y organizaciones.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Expedientes y trámites municipales', 'Hacé clic aquí para consultar el estado de un expediente, documentación requerida o problemas con la derivación de un trámite.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Atención y funcionamiento del portal', 'Hacé clic aquí para pedir ayuda con el portal, informar errores técnicos, problemas con notificaciones o realizar una consulta general.', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (name) DO UPDATE SET
    description = EXCLUDED.description,
    active = TRUE,
    updated_at = CURRENT_TIMESTAMP;

-- ================================================================
-- SUBCATEGORIES (52)
-- ================================================================
WITH seed(category_name, subcategory_name) AS (
    VALUES
        ('Calles, veredas e infraestructura urbana', 'Calles y Pavimento'),
        ('Calles, veredas e infraestructura urbana', 'Veredas'),
        ('Calles, veredas e infraestructura urbana', 'Desagües'),
        ('Calles, veredas e infraestructura urbana', 'Edificios Municipales'),
        ('Alumbrado y equipamiento urbano', 'Alumbrado público'),
        ('Alumbrado y equipamiento urbano', 'Mobiliario urbano'),
        ('Alumbrado y equipamiento urbano', 'Espacios públicos'),
        ('Limpieza, residuos y servicios urbanos', 'Recolección domiciliaria'),
        ('Limpieza, residuos y servicios urbanos', 'Residuos voluminosos'),
        ('Limpieza, residuos y servicios urbanos', 'Contenedores'),
        ('Limpieza, residuos y servicios urbanos', 'Limpieza urbana'),
        ('Limpieza, residuos y servicios urbanos', 'Reciclaje'),
        ('Arbolado, plazas y espacios verdes', 'Arbolado público'),
        ('Arbolado, plazas y espacios verdes', 'Plazas y parques'),
        ('Ambiente y convivencia urbana', 'Ruidos molestos'),
        ('Ambiente y convivencia urbana', 'Contaminación'),
        ('Ambiente y convivencia urbana', 'Higiene'),
        ('Ambiente y convivencia urbana', 'Ocupación del espacio público'),
        ('Comercios, habilitaciones e inspecciones', 'Habilitación comercial'),
        ('Comercios, habilitaciones e inspecciones', 'Inspecciones'),
        ('Comercios, habilitaciones e inspecciones', 'Denuncias comerciales'),
        ('Comercios, habilitaciones e inspecciones', 'Clausuras e intimaciones'),
        ('Tránsito y seguridad vial', 'Semáforos'),
        ('Tránsito y seguridad vial', 'Señalización'),
        ('Tránsito y seguridad vial', 'Seguridad vial'),
        ('Tránsito y seguridad vial', 'Estacionamiento'),
        ('Tránsito y seguridad vial', 'Cortes de calle'),
        ('Tránsito y seguridad vial', 'Incidentes viales'),
        ('Infracciones y vehículos retenidos', 'Infracciones'),
        ('Infracciones y vehículos retenidos', 'Vehículos detenidos'),
        ('Tasas, tributos y pagos municipales', 'Boletas y liquidaciones'),
        ('Tasas, tributos y pagos municipales', 'Pagos'),
        ('Tasas, tributos y pagos municipales', 'Deudas'),
        ('Tasas, tributos y pagos municipales', 'Planes de pago'),
        ('Tasas, tributos y pagos municipales', 'Exenciones'),
        ('Desarrollo social y asistencia comunitaria', 'Programas sociales'),
        ('Desarrollo social y asistencia comunitaria', 'Beneficios'),
        ('Desarrollo social y asistencia comunitaria', 'Visitas sociales'),
        ('Desarrollo social y asistencia comunitaria', 'Situaciones urgentes'),
        ('Salud comunitaria y actividades municipales', 'Turnos municipales'),
        ('Salud comunitaria y actividades municipales', 'Campañas'),
        ('Salud comunitaria y actividades municipales', 'Centro municipales'),
        ('Datos ciudadanos, organizaciones y acceso', 'Datos personales'),
        ('Datos ciudadanos, organizaciones y acceso', 'Representación'),
        ('Datos ciudadanos, organizaciones y acceso', 'Cuenta de acceso'),
        ('Expedientes y trámites municipales', 'Seguimiento'),
        ('Expedientes y trámites municipales', 'Documentación'),
        ('Expedientes y trámites municipales', 'Derivaciones'),
        ('Atención y funcionamiento del portal', 'Uso del portal'),
        ('Atención y funcionamiento del portal', 'Seguimiento de tickets'),
        ('Atención y funcionamiento del portal', 'Notificaciones'),
        ('Atención y funcionamiento del portal', 'Atención municipal')
)
INSERT INTO subcategories (category_id, name, description, active)
SELECT c.id, seed.subcategory_name, NULL, TRUE
FROM seed
JOIN categories c ON c.name = seed.category_name
ON CONFLICT (category_id, name) DO UPDATE SET
    active = TRUE;

-- ================================================================
-- REQUEST TYPES VIGENTES (110)
-- Se reutilizan los códigos de V4 para los 108 Request Types existentes.
-- Códigos nuevos:
--   INFORMAR_UN_PROBLEMA_CON_UN_BENEFICIO
--   INFORMAR_UN_PROBLEMA_CON_ALGUNA_DOCUMENTACION
-- ================================================================
WITH seed(
    category_name,
    subcategory_name,
    code,
    name,
    description,
    ticket_type,
    responsible_area_id
) AS (
    VALUES
        ('Calles, veredas e infraestructura urbana', 'Calles y Pavimento', 'INFORMAR_UN_BACHE', 'Informar un bache', 'Reclamo referente a informar un bache - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Calles, veredas e infraestructura urbana', 'Calles y Pavimento', 'INFORMAR_UNA_CALLE_DETERIORADA', 'Informar una calle deteriorada', 'Reclamo referente a informar una calle deteriorada - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Calles, veredas e infraestructura urbana', 'Calles y Pavimento', 'SOLICITAR_REPARACION_DE_CALLE', 'Solicitar reparación de calle', 'Solicitud referente a solicitar reparación de calle - Área asignada: Obras Públicas.', 'REQUEST', 'Obras Públicas'),
        ('Calles, veredas e infraestructura urbana', 'Calles y Pavimento', 'INFORMAR_UNA_OBRA_INCONCLUSA', 'Informar una obra inconclusa', 'Reclamo referente a informar una obra inconclusa - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Calles, veredas e infraestructura urbana', 'Veredas', 'INFORMAR_UNA_VEREDA_ROTA', 'Informar una vereda rota', 'Reclamo referente a informar una vereda rota - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Calles, veredas e infraestructura urbana', 'Veredas', 'SOLICITAR_REPARACION_DE_VEREDA_MUNICIPAL', 'Solicitar reparación de vereda municipal', 'Solicitud referente a solicitar reparación de vereda municipal - Área asignada: Obras Públicas.', 'REQUEST', 'Obras Públicas'),
        ('Calles, veredas e infraestructura urbana', 'Veredas', 'INFORMAR_UNA_VEREDA_OBSTRUIDA', 'Informar una vereda obstruida', 'Reclamo referente a informar una vereda obstruida - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Calles, veredas e infraestructura urbana', 'Desagües', 'INFORMAR_UN_DESAGUE_TAPADO', 'Informar un desagüe tapado', 'Reclamo referente a informar un desagüe tapado - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Calles, veredas e infraestructura urbana', 'Desagües', 'INFORMAR_ACUMULACION_DE_AGUA', 'Informar acumulación de agua', 'Reclamo referente a informar acumulación de agua - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Calles, veredas e infraestructura urbana', 'Desagües', 'SOLICITAR_LIMPIEZA_DE_DESAGUE', 'Solicitar limpieza de desagüe', 'Solicitud referente a solicitar limpieza de desagüe - Área asignada: Obras Públicas.', 'REQUEST', 'Obras Públicas'),
        ('Calles, veredas e infraestructura urbana', 'Edificios Municipales', 'INFORMAR_DANOS_EN_UN_EDIFICIO_MUNICIPAL', 'Informar daños en un edificio municipal', 'Reclamo referente a informar daños en un edificio municipal - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Calles, veredas e infraestructura urbana', 'Edificios Municipales', 'INFORMAR_PROBLEMAS_DE_ACCESIBILIDAD', 'Informar problemas de accesibilidad', 'Reclamo referente a informar problemas de accesibilidad - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Alumbrado y equipamiento urbano', 'Alumbrado público', 'INFORMAR_UNA_LUMINARIA_APAGADA', 'Informar una luminaria apagada', 'Reclamo referente a informar una luminaria apagada - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Alumbrado y equipamiento urbano', 'Alumbrado público', 'INFORMAR_UNA_LUMINARIA_INTERMITENTE', 'Informar una luminaria intermitente', 'Reclamo referente a informar una luminaria intermitente - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Alumbrado y equipamiento urbano', 'Alumbrado público', 'INFORMAR_UNA_COLUMNA_DANADA', 'Informar una columna dañada', 'Reclamo referente a informar una columna dañada - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Alumbrado y equipamiento urbano', 'Alumbrado público', 'INFORMAR_UNA_CABLES_EXPUESTOS', 'Informar una cables expuestos', 'Reclamo referente a informar una cables expuestos - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Alumbrado y equipamiento urbano', 'Alumbrado público', 'SOLICITAR_NUEVA_ILUMINACION', 'Solicitar nueva iluminación', 'Solicitud referente a solicitar nueva iluminación - Área asignada: Obras Públicas.', 'REQUEST', 'Obras Públicas'),
        ('Alumbrado y equipamiento urbano', 'Mobiliario urbano', 'INFORMAR_MOBILIARIO_DANADO', 'Informar mobiliario dañado', 'Reclamo referente a informar mobiliario dañado - Área asignada: Obras Públicas.', 'COMPLAINT', 'Obras Públicas'),
        ('Alumbrado y equipamiento urbano', 'Mobiliario urbano', 'SOLICITA_INSTALACION_DE_MOBILIARIO', 'Solicita instalación de mobiliario', 'Solicitud referente a solicita instalación de mobiliario - Área asignada: Obras Públicas.', 'REQUEST', 'Obras Públicas'),
        ('Alumbrado y equipamiento urbano', 'Espacios públicos', 'SUGERIR_MEJORAS_EN_UN_ESPACIO_PUBLICO', 'Sugerir mejoras en un espacio público', 'Sugerencia referente a sugerir mejoras en un espacio público - Área asignada: Obras Públicas.', 'SUGGESTION', 'Obras Públicas'),
        ('Limpieza, residuos y servicios urbanos', 'Recolección domiciliaria', 'INFORMAR_FALTA_DE_RECOLECCION', 'Informar falta de recolección', 'Reclamo referente a informar falta de recolección - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Limpieza, residuos y servicios urbanos', 'Recolección domiciliaria', 'INFORMAR_RECOLECCION_FUERA_DE_HORARIO', 'Informar recolección fuera de horario', 'Reclamo referente a informar recolección fuera de horario - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Limpieza, residuos y servicios urbanos', 'Residuos voluminosos', 'SOLICITAR_RETIRO_DE_RESIDUOS_VOLUMINOSOS', 'Solicitar retiro de residuos voluminosos', 'Solicitud referente a solicitar retiro de residuos voluminosos - Área asignada: Ambiente y Servicios Urbanos.', 'REQUEST', 'Ambiente y Servicios Urbanos'),
        ('Limpieza, residuos y servicios urbanos', 'Contenedores', 'INFORMAR_UN_CONTENEDOR_DESBORDADO', 'Informar un contenedor desbordado', 'Reclamo referente a informar un contenedor desbordado - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Limpieza, residuos y servicios urbanos', 'Contenedores', 'INFORMAR_UN_CONTENEDOR_DANADO', 'Informar un contenedor dañado', 'Reclamo referente a informar un contenedor dañado - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Limpieza, residuos y servicios urbanos', 'Contenedores', 'SOLICITAR_LIMPIEZA_DE_UN_CONTENEDOR', 'Solicitar limpieza de un contenedor', 'Solicitud referente a solicitar limpieza de un contenedor - Área asignada: Ambiente y Servicios Urbanos.', 'REQUEST', 'Ambiente y Servicios Urbanos'),
        ('Limpieza, residuos y servicios urbanos', 'Contenedores', 'SOLICITAR_REUBICACION_DE_UN_CONTENEDOR', 'Solicitar reubicación de un contenedor', 'Solicitud referente a solicitar reubicación de un contenedor - Área asignada: Ambiente y Servicios Urbanos.', 'REQUEST', 'Ambiente y Servicios Urbanos'),
        ('Limpieza, residuos y servicios urbanos', 'Contenedores', 'SOLICITAR_UN_NUEVO_CONTENEDOR', 'Solicitar un nuevo contenedor', 'Solicitud referente a solicitar un nuevo contenedor - Área asignada: Ambiente y Servicios Urbanos.', 'REQUEST', 'Ambiente y Servicios Urbanos'),
        ('Limpieza, residuos y servicios urbanos', 'Limpieza urbana', 'INFORMAR_SUCIEDAD_EN_LA_VIA_PUBLICA', 'Informar suciedad en la vía pública', 'Reclamo referente a informar suciedad en la vía pública - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Limpieza, residuos y servicios urbanos', 'Limpieza urbana', 'INFORMAR_UN_MICROBASURAL', 'Informar un microbasural', 'Reclamo referente a informar un microbasural - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Limpieza, residuos y servicios urbanos', 'Limpieza urbana', 'SOLICITAR_UN_OPERATIVO_DE_LIMPIEZA', 'Solicitar un operativo de limpieza', 'Solicitud referente a solicitar un operativo de limpieza - Área asignada: Ambiente y Servicios Urbanos.', 'REQUEST', 'Ambiente y Servicios Urbanos'),
        ('Limpieza, residuos y servicios urbanos', 'Reciclaje', 'SUGERIR_UN_NUEVO_PUNTO_VERDE', 'Sugerir un nuevo punto verde', 'Sugerencia referente a sugerir un nuevo punto verde - Área asignada: Ambiente y Servicios Urbanos.', 'SUGGESTION', 'Ambiente y Servicios Urbanos'),
        ('Arbolado, plazas y espacios verdes', 'Arbolado público', 'INFORMAR_UN_ARBOL_CON_RIESGO_DE_CAIDA', 'Informar un árbol con riesgo de caída', 'Reclamo referente a informar un árbol con riesgo de caída - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Arbolado, plazas y espacios verdes', 'Arbolado público', 'INFORMAR_UNA_RAMA_PELIGROSA', 'Informar una rama peligrosa', 'Reclamo referente a informar una rama peligrosa - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Arbolado, plazas y espacios verdes', 'Arbolado público', 'SOLICITAR_PODA', 'Solicitar poda', 'Solicitud referente a solicitar poda - Área asignada: Ambiente y Servicios Urbanos.', 'REQUEST', 'Ambiente y Servicios Urbanos'),
        ('Arbolado, plazas y espacios verdes', 'Arbolado público', 'SOLICITAR_EXTRACCION_DE_UN_ARBOL', 'Solicitar extracción de un árbol', 'Solicitud referente a solicitar extracción de un árbol - Área asignada: Ambiente y Servicios Urbanos.', 'REQUEST', 'Ambiente y Servicios Urbanos'),
        ('Arbolado, plazas y espacios verdes', 'Arbolado público', 'SOLICITAR_PLANTACION_DE_UN_ARBOL', 'Solicitar plantación de un arbol', 'Solicitud referente a solicitar plantación de un arbol - Área asignada: Ambiente y Servicios Urbanos.', 'REQUEST', 'Ambiente y Servicios Urbanos'),
        ('Arbolado, plazas y espacios verdes', 'Arbolado público', 'DENUNCIAR_DANO_AL_ARBOLADO', 'Denunciar daño al arbolado', 'Reclamo referente a denunciar daño al arbolado - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Arbolado, plazas y espacios verdes', 'Plazas y parques', 'INFORMAR_FALTA_DE_MANTENIMIENTO', 'Informar falta de mantenimiento', 'Reclamo referente a informar falta de mantenimiento - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Arbolado, plazas y espacios verdes', 'Plazas y parques', 'INFORMAR_JUEGOS_DANADOS', 'Informar juegos dañados', 'Reclamo referente a informar juegos dañados - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Arbolado, plazas y espacios verdes', 'Plazas y parques', 'INFORMAR_PROBLEMAS_DE_RIESGO', 'Informar problemas de riesgo', 'Reclamo referente a informar problemas de riesgo - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Arbolado, plazas y espacios verdes', 'Plazas y parques', 'SUGERIR_MEJORAS_PARA_UNA_PLAZA', 'Sugerir mejoras para una plaza', 'Sugerencia referente a sugerir mejoras para una plaza - Área asignada: Ambiente y Servicios Urbanos.', 'SUGGESTION', 'Ambiente y Servicios Urbanos'),
        ('Ambiente y convivencia urbana', 'Ruidos molestos', 'DENUNCIAR_RUIDOS_DE_UN_COMERCIO', 'Denunciar ruidos de un comercio', 'Reclamo referente a denunciar ruidos de un comercio - Área asignada: Habilitaciones y Control Comercial.', 'COMPLAINT', 'Habilitaciones y Control Comercial'),
        ('Ambiente y convivencia urbana', 'Ruidos molestos', 'DENUNCIAR_RUIDOS_EN_LA_VIA_PUBLICA', 'Denunciar ruidos en la vía pública', 'Reclamo referente a denunciar ruidos en la vía pública - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Ambiente y convivencia urbana', 'Contaminación', 'DENUNCIAR_VERTIDO_DE_LIQUIDOS_O_RESIDUOS', 'Denunciar vertido de líquidos o residuos', 'Reclamo referente a denunciar vertido de líquidos o residuos - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Ambiente y convivencia urbana', 'Contaminación', 'DENUNCIAR_HUMO_O_OLORES_MOLESTOS', 'Denunciar humo o olores molestos', 'Reclamo referente a denunciar humo o olores molestos - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Ambiente y convivencia urbana', 'Contaminación', 'INFORMAR_PRESENCIA_DE_RESIDUOS_PELIGROSOS', 'Informar presencia de residuos peligrosos', 'Reclamo referente a informar presencia de residuos peligrosos - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Ambiente y convivencia urbana', 'Higiene', 'DENUNCIAR_CONDICIONES_INSALUBRE', 'Denunciar condiciones insalubre', 'Reclamo referente a denunciar condiciones insalubre - Área asignada: Ambiente y Servicios Urbanos.', 'COMPLAINT', 'Ambiente y Servicios Urbanos'),
        ('Ambiente y convivencia urbana', 'Higiene', 'DENUNCIAR_FALTA_DE_HIGIENE_EN_UN_COMERCIO', 'Denunciar falta de higiene en un comercio', 'Reclamo referente a denunciar falta de higiene en un comercio - Área asignada: Habilitaciones y Control Comercial.', 'COMPLAINT', 'Habilitaciones y Control Comercial'),
        ('Ambiente y convivencia urbana', 'Ocupación del espacio público', 'DENUNCIAR_OCUPACION_IRREGULAR_OKUPAS', 'Denunciar ocupación irregular (okupas)', 'Reclamo referente a denunciar ocupación irregular (okupas) - Área asignada: Habilitaciones y Control Comercial.', 'COMPLAINT', 'Habilitaciones y Control Comercial'),
        ('Comercios, habilitaciones e inspecciones', 'Habilitación comercial', 'INFORMAR_UN_PROBLEMA_CON_LA_DOCUMENTACION', 'Informar un problema con la documentación', 'Reclamo referente a informar un problema con la documentación - Área asignada: Habilitaciones y Control Comercial.', 'COMPLAINT', 'Habilitaciones y Control Comercial'),
        ('Comercios, habilitaciones e inspecciones', 'Habilitación comercial', 'SOLICITAR_ORIENTACION_PARA_RENOVAR_UNA_HABILITACION', 'Solicitar orientación para renovar una habilitación', 'Solicitud referente a solicitar orientación para renovar una habilitación - Área asignada: Habilitaciones y Control Comercial.', 'REQUEST', 'Habilitaciones y Control Comercial'),
        ('Comercios, habilitaciones e inspecciones', 'Inspecciones', 'SOLICITAR_REPROGRAMACION_DE_UNA_INSPECCION', 'Solicitar reprogramación de una inspección', 'Solicitud referente a solicitar reprogramación de una inspección - Área asignada: Habilitaciones y Control Comercial.', 'REQUEST', 'Habilitaciones y Control Comercial'),
        ('Comercios, habilitaciones e inspecciones', 'Inspecciones', 'RECLAMAR_POR_UNA_DEMORA_EN_LA_INSPECCION', 'Reclamar por una demora en la inspección', 'Reclamo referente a reclamar por una demora en la inspección - Área asignada: Habilitaciones y Control Comercial.', 'COMPLAINT', 'Habilitaciones y Control Comercial'),
        ('Comercios, habilitaciones e inspecciones', 'Denuncias comerciales', 'DENUNCIAR_UN_COMERCIO_SIN_HABILITACION', 'Denunciar un comercio sin habilitación', 'Reclamo referente a denunciar un comercio sin habilitación - Área asignada: Habilitaciones y Control Comercial.', 'COMPLAINT', 'Habilitaciones y Control Comercial'),
        ('Comercios, habilitaciones e inspecciones', 'Denuncias comerciales', 'DENUNCIAR_INCUMPLIMIENTOS_HORARIOS', 'Denunciar incumplimientos horarios', 'Reclamo referente a denunciar incumplimientos horarios - Área asignada: Habilitaciones y Control Comercial.', 'COMPLAINT', 'Habilitaciones y Control Comercial'),
        ('Comercios, habilitaciones e inspecciones', 'Denuncias comerciales', 'DENUNCIAR_ACTIVIDAD_NO_AUTORIZADA', 'Denunciar actividad no autorizada', 'Reclamo referente a denunciar actividad no autorizada - Área asignada: Habilitaciones y Control Comercial.', 'COMPLAINT', 'Habilitaciones y Control Comercial'),
        ('Comercios, habilitaciones e inspecciones', 'Clausuras e intimaciones', 'INFORMAR_UN_POSIBLE_INCUMPLIMIENTO_DE_CLAUSURA', 'Informar un posible incumplimiento de clausura', 'Reclamo referente a informar un posible incumplimiento de clausura - Área asignada: Habilitaciones y Control Comercial.', 'COMPLAINT', 'Habilitaciones y Control Comercial'),
        ('Tránsito y seguridad vial', 'Semáforos', 'INFORMAR_UN_SEMAFORO_FUERA_DE_SERVICIO', 'Informar un semáforo fuera de servicio', 'Reclamo referente a informar un semáforo fuera de servicio - Área asignada: Tránsito.', 'COMPLAINT', 'Tránsito'),
        ('Tránsito y seguridad vial', 'Semáforos', 'INFORMAR_UN_SEMAFORO_DESCOORDINADO', 'Informar un semáforo descoordinado', 'Reclamo referente a informar un semáforo descoordinado - Área asignada: Tránsito.', 'COMPLAINT', 'Tránsito'),
        ('Tránsito y seguridad vial', 'Señalización', 'INFORMAR_UNA_SENAL_DANADA_O_FALTANTE', 'Informar una señal dañada o faltante', 'Reclamo referente a informar una señal dañada o faltante - Área asignada: Tránsito.', 'COMPLAINT', 'Tránsito'),
        ('Tránsito y seguridad vial', 'Señalización', 'SOLICITAR_NUEVA_SENALIZACION', 'Solicitar nueva señalización', 'Solicitud referente a solicitar nueva señalización - Área asignada: Tránsito.', 'REQUEST', 'Tránsito'),
        ('Tránsito y seguridad vial', 'Señalización', 'SUGERIR_CAMBIOS_DE_CIRCULACION', 'Sugerir cambios de circulación', 'Sugerencia referente a sugerir cambios de circulación - Área asignada: Tránsito.', 'SUGGESTION', 'Tránsito'),
        ('Tránsito y seguridad vial', 'Seguridad vial', 'INFORMAR_UNA_SITUACION_VIAL', 'Informar una situación vial', 'Reclamo referente a informar una situación vial - Área asignada: Tránsito.', 'COMPLAINT', 'Tránsito'),
        ('Tránsito y seguridad vial', 'Seguridad vial', 'SOLICITAR_UN_REDUCTOR_DE_VELOCIDAD', 'Solicitar un reductor de velocidad', 'Solicitud referente a solicitar un reductor de velocidad - Área asignada: Tránsito.', 'REQUEST', 'Tránsito'),
        ('Tránsito y seguridad vial', 'Seguridad vial', 'SOLICITAR_UN_OPERATIO_DE_TRANSITO', 'Solicitar un operatio de tránsito', 'Solicitud referente a solicitar un operatio de tránsito - Área asignada: Tránsito.', 'REQUEST', 'Tránsito'),
        ('Tránsito y seguridad vial', 'Estacionamiento', 'DENUNCIAR_ESTACIONAMIENTO_INDEBIDO', 'Denunciar estacionamiento indebido', 'Reclamo referente a denunciar estacionamiento indebido - Área asignada: Tránsito.', 'COMPLAINT', 'Tránsito'),
        ('Tránsito y seguridad vial', 'Estacionamiento', 'INFORMAR_UN_PROBLEMA_CON_EL_ESTACIONAMIETO_MEDIDO', 'Informar un problema con el estacionamieto medido', 'Reclamo referente a informar un problema con el estacionamieto medido - Área asignada: Tránsito.', 'COMPLAINT', 'Tránsito'),
        ('Tránsito y seguridad vial', 'Cortes de calle', 'INFORMAR_PROBLEMAS_OCASIONADOS_POR_UN_CORTE', 'Informar problemas ocasionados por un corte', 'Reclamo referente a informar problemas ocasionados por un corte - Área asignada: Tránsito.', 'COMPLAINT', 'Tránsito'),
        ('Tránsito y seguridad vial', 'Incidentes viales', 'INFORMAR_UN_INCIDENTE_VIAL', 'Informar un incidente vial', 'Reclamo referente a informar un incidente vial - Área asignada: Tránsito.', 'COMPLAINT', 'Tránsito'),
        ('Infracciones y vehículos retenidos', 'Infracciones', 'INFORMAR_UN_ERROR_EN_LOS_DATOS_DEL_ACTA', 'Informar un error en los datos del acta', 'Reclamo referente a informar un error en los datos del acta - Área asignada: Tránsito.', 'COMPLAINT', 'Tránsito'),
        ('Infracciones y vehículos retenidos', 'Infracciones', 'RECLAMAR_POR_UNA_DEMORA_EN_LA_RESOLUCION', 'Reclamar por una demora en la resolución', 'Reclamo referente a reclamar por una demora en la resolución - Área asignada: Tránsito.', 'COMPLAINT', 'Tránsito'),
        ('Infracciones y vehículos retenidos', 'Vehículos detenidos', 'RECLAMAR_POR_UNA_DEMORA_EN_LA_LIBERACION', 'Reclamar por una demora en la liberación', 'Reclamo referente a reclamar por una demora en la liberación - Área asignada: Tránsito.', 'COMPLAINT', 'Tránsito'),
        ('Tasas, tributos y pagos municipales', 'Boletas y liquidaciones', 'INFORMAR_UN_ERROR_EN_UNA_LIQUIDACION', 'Informar un error en una liquidación', 'Reclamo referente a informar un error en una liquidación - Área asignada: Rentas.', 'COMPLAINT', 'Rentas'),
        ('Tasas, tributos y pagos municipales', 'Boletas y liquidaciones', 'SOLICITAR_UNA_COPIA_DE_UNA_BOLETA', 'Solicitar una copia de una boleta', 'Solicitud referente a solicitar una copia de una boleta - Área asignada: Rentas.', 'REQUEST', 'Rentas'),
        ('Tasas, tributos y pagos municipales', 'Pagos', 'INFORMAR_UN_PAGO_NO_REGISTRADO', 'Informar un pago no registrado', 'Reclamo referente a informar un pago no registrado - Área asignada: Rentas.', 'COMPLAINT', 'Rentas'),
        ('Tasas, tributos y pagos municipales', 'Pagos', 'INFORMAR_UN_PAGO_INPUTADO_INCORRECTAMENTE', 'Informar un pago inputado incorrectamente', 'Reclamo referente a informar un pago inputado incorrectamente - Área asignada: Rentas.', 'COMPLAINT', 'Rentas'),
        ('Tasas, tributos y pagos municipales', 'Pagos', 'SOLICITAR_COMPROBANTE_DE_PAGO', 'Solicitar comprobante de pago', 'Solicitud referente a solicitar comprobante de pago - Área asignada: Rentas.', 'REQUEST', 'Rentas'),
        ('Tasas, tributos y pagos municipales', 'Deudas', 'RECLAMAR_POR_UNA_DEUDA_INCORRECTA', 'Reclamar por una deuda incorrecta', 'Reclamo referente a reclamar por una deuda incorrecta - Área asignada: Rentas.', 'COMPLAINT', 'Rentas'),
        ('Tasas, tributos y pagos municipales', 'Planes de pago', 'INFORMAR_UN_PROBLEMA_CON_UN_PLAN_DE_PAGO', 'Informar un problema con un plan de pago', 'Reclamo referente a informar un problema con un plan de pago - Área asignada: Rentas.', 'COMPLAINT', 'Rentas'),
        ('Tasas, tributos y pagos municipales', 'Exenciones', 'RECLAMAR_POR_UNA_DEMORA_EN_LA_RESOLUCION_RENTAS', 'Reclamar por una demora en la resolución', 'Reclamo referente a reclamar por una demora en la resolución - Área asignada: Rentas.', 'COMPLAINT', 'Rentas'),
        ('Desarrollo social y asistencia comunitaria', 'Programas sociales', 'SOLICITAR_ASISTENCIA_SOCIAL', 'Solicitar asistencia social', 'Solicitud referente a solicitar asistencia social - Área asignada: Desarrollo Social.', 'REQUEST', 'Desarrollo Social'),
        ('Desarrollo social y asistencia comunitaria', 'Beneficios', 'INFORMAR_UN_PROBLEMA_CON_UN_BENEFICIO', 'Informar un problema con un beneficio', 'Reclamo referente a informar un problema con un beneficio - Área asignada: Desarrollo Social.', 'COMPLAINT', 'Desarrollo Social'),
        ('Desarrollo social y asistencia comunitaria', 'Visitas sociales', 'SOLICITAR_REPROGRAMACION_DE_UNA_VISITA', 'Solicitar reprogramación de una visita', 'Solicitud referente a solicitar reprogramación de una visita - Área asignada: Desarrollo Social.', 'REQUEST', 'Desarrollo Social'),
        ('Desarrollo social y asistencia comunitaria', 'Situaciones urgentes', 'INFORMAR_UNA_SITUACION_DE_VULNERABILIDAD', 'Informar una situación de vulnerabilidad', 'Reclamo referente a informar una situación de vulnerabilidad - Área asignada: Desarrollo Social.', 'COMPLAINT', 'Desarrollo Social'),
        ('Desarrollo social y asistencia comunitaria', 'Situaciones urgentes', 'INFORMAR_UNA_PERSONA_EN_SITUACION_DE_CALLE', 'Informar una persona en situación de calle', 'Reclamo referente a informar una persona en situación de calle - Área asignada: Desarrollo Social.', 'COMPLAINT', 'Desarrollo Social'),
        ('Salud comunitaria y actividades municipales', 'Turnos municipales', 'INFORMAR_UN_PROBLEMA_DE_TURNO', 'Informar un problema de turno', 'Reclamo referente a informar un problema de turno - Área asignada: Desarrollo Social.', 'COMPLAINT', 'Desarrollo Social'),
        ('Salud comunitaria y actividades municipales', 'Turnos municipales', 'SOLICITAR_REPROGRAMACION_DE_TURNO', 'Solicitar reprogramación de turno', 'Solicitud referente a solicitar reprogramación de turno - Área asignada: Desarrollo Social.', 'REQUEST', 'Desarrollo Social'),
        ('Salud comunitaria y actividades municipales', 'Campañas', 'SUGERIR_UNA_ACTIVIDAD_COMUNITARIA', 'Sugerir una actividad comunitaria', 'Sugerencia referente a sugerir una actividad comunitaria - Área asignada: Desarrollo Social.', 'SUGGESTION', 'Desarrollo Social'),
        ('Salud comunitaria y actividades municipales', 'Centro municipales', 'RECLAMAR_POR_LA_ATENCION_RECIBIDA', 'Reclamar por la atención recibida', 'Reclamo referente a reclamar por la atención recibida - Área asignada: Desarrollo Social.', 'COMPLAINT', 'Desarrollo Social'),
        ('Datos ciudadanos, organizaciones y acceso', 'Datos personales', 'INFORMAR_DATOS_PERSONALES_INCORRECTOS', 'Informar datos personales incorrectos', 'Reclamo referente a informar datos personales incorrectos - Área asignada: Ciudadanos y Organizaciones.', 'COMPLAINT', 'Ciudadanos y Organizaciones'),
        ('Datos ciudadanos, organizaciones y acceso', 'Datos personales', 'SOLICITAR_ACTUALIZACION_DE_DATOS', 'Solicitar actualización de datos', 'Solicitud referente a solicitar actualización de datos - Área asignada: Ciudadanos y Organizaciones.', 'REQUEST', 'Ciudadanos y Organizaciones'),
        ('Datos ciudadanos, organizaciones y acceso', 'Datos personales', 'INFORMAR_UN_DOMICILIO_INCORRECTO', 'Informar un domicilio incorrecto', 'Reclamo referente a informar un domicilio incorrecto - Área asignada: Ciudadanos y Organizaciones.', 'COMPLAINT', 'Ciudadanos y Organizaciones'),
        ('Datos ciudadanos, organizaciones y acceso', 'Representación', 'INFORMAR_DATOS_INCORRECTOS_DE_UNA_ORGANIZACION', 'Informar datos incorrectos de una organización', 'Reclamo referente a informar datos incorrectos de una organización - Área asignada: Ciudadanos y Organizaciones.', 'COMPLAINT', 'Ciudadanos y Organizaciones'),
        ('Datos ciudadanos, organizaciones y acceso', 'Cuenta de acceso', 'INFORMAR_UN_PROBLEMA_PARA_INGRESAR', 'Informar un problema para ingresar', 'Reclamo referente a informar un problema para ingresar - Área asignada: Ciudadanos y Organizaciones.', 'COMPLAINT', 'Ciudadanos y Organizaciones'),
        ('Datos ciudadanos, organizaciones y acceso', 'Cuenta de acceso', 'INFORMAR_UN_BLOQUEO_DE_CUENTA', 'Informar un bloqueo de cuenta', 'Reclamo referente a informar un bloqueo de cuenta - Área asignada: Ciudadanos y Organizaciones.', 'COMPLAINT', 'Ciudadanos y Organizaciones'),
        ('Expedientes y trámites municipales', 'Seguimiento', 'RECLAMAR_POR_FALTA_DE_ACTUALIZACION', 'Reclamar por falta de actualización', 'Reclamo referente a reclamar por falta de actualización - Área asignada: Ciudadanos y Organizaciones.', 'COMPLAINT', 'Ciudadanos y Organizaciones'),
        ('Expedientes y trámites municipales', 'Documentación', 'INFORMAR_UN_PROBLEMA_AL_ADJUNTAR_DOCUMENTOS', 'Informar un problema al adjuntar documentos', 'Reclamo referente a informar un problema al adjuntar documentos - Área asignada: Ciudadanos y Organizaciones.', 'COMPLAINT', 'Ciudadanos y Organizaciones'),
        ('Expedientes y trámites municipales', 'Documentación', 'INFORMAR_UN_PROBLEMA_CON_ALGUNA_DOCUMENTACION', 'Informar un problema con alguna documentación', 'Reclamo referente a informar un problema con alguna documentación - Área asignada: Ciudadanos y Organizaciones.', 'COMPLAINT', 'Ciudadanos y Organizaciones'),
        ('Expedientes y trámites municipales', 'Derivaciones', 'RECLAMAR_POR_UNA_DERIVACION_INCORRECTA', 'Reclamar por una derivación incorrecta', 'Reclamo referente a reclamar por una derivación incorrecta - Área asignada: Call Center.', 'COMPLAINT', 'Call Center'),
        ('Atención y funcionamiento del portal', 'Uso del portal', 'PEDIR_ORIENTACION_PARA_SELECCIONAR_UNA_OPCION_O_COMPLETAR_EL_FORMULARIO', 'Pedir orientación para seleccionar una opción o completar el formulario', 'Consulta referente a pedir orientación para seleccionar una opción o completar el formulario - Área asignada: Call Center.', 'INQUIRY', 'Call Center'),
        ('Atención y funcionamiento del portal', 'Uso del portal', 'INFORMAR_UN_ERROR_EN_EL_PORTAL', 'Informar un error en el portal', 'Reclamo referente a informar un error en el portal - Área asignada: Call Center.', 'COMPLAINT', 'Call Center'),
        ('Atención y funcionamiento del portal', 'Uso del portal', 'INFORMAR_UN_PROBLEMA_AL_ADJUNTAR_ARCHIVOS', 'Informar un problema al adjuntar archivos', 'Reclamo referente a informar un problema al adjuntar archivos - Área asignada: Call Center.', 'COMPLAINT', 'Call Center'),
        ('Atención y funcionamiento del portal', 'Seguimiento de tickets', 'CONSULTAR_UN_NUMERO_DE_SEGUIMIENTO', 'Consultar un número de seguimiento', 'Consulta referente a consultar un número de seguimiento - Área asignada: Call Center.', 'INQUIRY', 'Call Center'),
        ('Atención y funcionamiento del portal', 'Seguimiento de tickets', 'INFORMAR_QUE_EL_ESTADO_NO_SE_ACTUALIZA', 'Informar que el estado no se actualiza', 'Reclamo referente a informar que el estado no se actualiza - Área asignada: Call Center.', 'COMPLAINT', 'Call Center'),
        ('Atención y funcionamiento del portal', 'Notificaciones', 'INFORMAR_QUE_NO_RECIBI_UNA_NOTIFICACION', 'Informar que no recibí una notificación', 'Reclamo referente a informar que no recibí una notificación - Área asignada: Call Center.', 'COMPLAINT', 'Call Center'),
        ('Atención y funcionamiento del portal', 'Notificaciones', 'INFORMAR_UNA_NOTIFICACION_INCORRECTA', 'Informar una notificación incorrecta', 'Reclamo referente a informar una notificación incorrecta - Área asignada: Call Center.', 'COMPLAINT', 'Call Center'),
        ('Atención y funcionamiento del portal', 'Atención municipal', 'RECLAMAR_POR_LA_ATENCION_RECIBIDA_CALL_CENTER', 'Reclamar por la atención recibida', 'Reclamo referente a reclamar por la atención recibida - Área asignada: Call Center.', 'COMPLAINT', 'Call Center'),
        ('Atención y funcionamiento del portal', 'Atención municipal', 'SUGERIR_UNA_MEJORA_EN_EL_PORTAL', 'Sugerir una mejora en el portal', 'Sugerencia referente a sugerir una mejora en el portal - Área asignada: Call Center.', 'SUGGESTION', 'Call Center'),
        ('Atención y funcionamiento del portal', 'Atención municipal', 'REALIZAR_UNA_CONSULTA_GENERAL', 'Realizar una consulta general', 'Consulta referente a realizar una consulta general - Área asignada: Call Center.', 'INQUIRY', 'Call Center')
)
INSERT INTO request_types (
    code,
    subcategory_id,
    name,
    description,
    ticket_type,
    responsible_area_id,
    minimum_priority,
    base_risk,
    allows_anonymous,
    requires_location,
    active,
    affected_population_factor
)
SELECT
    seed.code,
    s.id,
    seed.name,
    seed.description,
    seed.ticket_type,
    seed.responsible_area_id,
    'LOW',
    'LOW',
    FALSE,
    FALSE,
    TRUE,
    0.0000::numeric
FROM seed
JOIN categories c
    ON c.name = seed.category_name
JOIN subcategories s
    ON s.category_id = c.id
   AND s.name = seed.subcategory_name
ON CONFLICT (code) DO UPDATE SET
    subcategory_id = EXCLUDED.subcategory_id,
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    ticket_type = EXCLUDED.ticket_type,
    responsible_area_id = EXCLUDED.responsible_area_id,
    active = TRUE;

-- ================================================================
-- REQUEST TYPES RETIRADOS RESPECTO DE V4 (31)
-- Primero se desactivan todos. Al final de la migración se eliminan físicamente
-- únicamente aquellos que no tengan tickets asociados. Los que tengan referencias
-- históricas permanecen con active=FALSE.
-- ================================================================
UPDATE request_types
SET active = FALSE
WHERE code IN (

    'CONSULTAR_COMO_DISPONER_RESIDUOS_ESPECIALES', -- Limpieza, residuos y servicios urbanos / Residuos voluminosos / Consultar cómo disponer residuos especiales
    'CONSULTAR_PUNTOS_VERDES', -- Limpieza, residuos y servicios urbanos / Reciclaje / Consultar puntos verdes
    'CONSULTAR_REQUISITOS_DE_HABILITACION', -- Comercios, habilitaciones e inspecciones / Habilitación comercial / Consultar requisitos de habilitación
    'CONSULTAR_ESTADO_DE_UNA_HABILITACION', -- Comercios, habilitaciones e inspecciones / Habilitación comercial / Consultar estado de una habilitación
    'CONSULTAR_UNA_INSPECCION_PROGRAMADA', -- Comercios, habilitaciones e inspecciones / Inspecciones / Consultar una inspección programada
    'CONSULTAR_UNA_INTIMACION_O_CLAUSURA', -- Comercios, habilitaciones e inspecciones / Clausuras e intimaciones / Consultar una intimación o clausura
    'CONSULTAR_ZONAS_HORARIOS_O_TARIFAS', -- Tránsito y seguridad vial / Estacionamiento / Consultar zonas, horarios o tarifas
    'CONSULTAR_UN_CORTE_DE_CALLE', -- Tránsito y seguridad vial / Cortes de calle / Consultar un corte de calle
    'CONSULTAR_UNA_INFRACCION', -- Infracciones y vehículos retenidos / Infracciones / Consultar una infracción
    'CONSULTAR_COMO_PRESENTAR_UN_DESCARGO', -- Infracciones y vehículos retenidos / Infracciones / Consultar cómo presentar un descargo
    'CONSULTAR_REQUISITOS_DE_LIBERACION', -- Infracciones y vehículos retenidos / Vehículos detenidos / Consultar requisitos de liberación
    'CONSULTAR_ESTADO_DE_UN_VEHICULO_RETENIDO', -- Infracciones y vehículos retenidos / Vehículos detenidos / Consultar estado de un vehículo retenido
    'CONSULTAR_UNA_BOLETA_MUNICIPAL', -- Tasas, tributos y pagos municipales / Boletas y liquidaciones / Consultar una boleta municipal
    'CONSULTAR_DEUDA_MUNICIPAL', -- Tasas, tributos y pagos municipales / Deudas / Consultar deuda municipal
    'CONSULTAR_OPCIONES_DE_FINANCIACION', -- Tasas, tributos y pagos municipales / Planes de pago / Consultar opciones de financiación
    'CONSULTAR_REQUISITOS_DE_UNA_EXENCION', -- Tasas, tributos y pagos municipales / Exenciones / Consultar requisitos de una exención
    'CONSULTAR_ESTADO_DE_UNA_SOLICITUD_DE_EXENCION', -- Tasas, tributos y pagos municipales / Exenciones / Consultar estado de una solicitud de exención
    'CONSULTAR_PROGRAMAS_DISPONIBLES', -- Desarrollo social y asistencia comunitaria / Programas sociales / Consultar programas disponibles
    'CONSULTAR_REQUISITOS_DE_UN_BENEFICIO', -- Desarrollo social y asistencia comunitaria / Programas sociales / Consultar requisitos de un beneficio
    'CONSULTAR_ESTADO_DE_UNA_SOLICITUD', -- Desarrollo social y asistencia comunitaria / Programas sociales / Consultar estado de una solicitud
    'INFORMAR_UN_PROBLEMA_CON_LA_DOCUMENTACION_DESARROLLO_SOCIAL', -- Desarrollo social y asistencia comunitaria / Programas sociales / Informar un problema con la documentación
    'RECLAMAR_POR_UNA_DEMORA_EN_LA_EVALUACION', -- Desarrollo social y asistencia comunitaria / Beneficios / Reclamar por una demora en la evaluación
    'INFORMAR_UN_PROBLEMA_CON_UN_BENEFICIO_OTORGADO', -- Desarrollo social y asistencia comunitaria / Beneficios / Informar un problema con un beneficio otorgado
    'CONSULTAR_UNA_VISITA_PROGRAMADA', -- Desarrollo social y asistencia comunitaria / Visitas sociales / Consultar una visita programada
    'CONSULTAR_DISPONIBILIDAD_DE_TURNOS', -- Salud comunitaria y actividades municipales / Turnos municipales / Consultar disponibilidad de turnos
    'CONSULTAR_CAMPANAS_COMUNITARIAS', -- Salud comunitaria y actividades municipales / Campañas / Consultar campañas comunitarias
    'CONSULTAR_UNA_REPRESENTACION', -- Datos ciudadanos, organizaciones y acceso / Representación / Consultar una representación
    'CONSULTAR_COMO_REGISTRARSE', -- Datos ciudadanos, organizaciones y acceso / Cuenta de acceso / Consultar cómo registrarse
    'CONSULTAR_ESTADO_DE_UN_EXPEDIENTE', -- Expedientes y trámites municipales / Seguimiento / Consultar estado de un expediente
    'CONSULTAR_DOCUMENTACION_REQUERIDA', -- Expedientes y trámites municipales / Documentación / Consultar documentación requerida
    'CONSULTAR_EL_AREA_RESPONSABLE' -- Expedientes y trámites municipales / Derivaciones / Consultar el área responsable
);


INSERT INTO form_templates (
    request_type_id,
    version,
    active,
    created_at
)
SELECT
    rt.id,
    1,
    TRUE,
    CURRENT_TIMESTAMP
FROM request_types rt
WHERE rt.code IN (
                  'INFORMAR_UN_PROBLEMA_CON_UN_BENEFICIO',
                  'INFORMAR_UN_PROBLEMA_CON_ALGUNA_DOCUMENTACION'
    )
    ON CONFLICT (request_type_id, version) DO NOTHING;


-- ================================================================
-- FORM FIELDS PARA NUEVOS REQUEST TYPES
-- ================================================================

WITH seed (
           request_type_code,
           field_code,
           label,
           field_type,
           required,
           display_order,
           config
    ) AS (
    VALUES

        -- ------------------------------------------------------------
        -- Informar un problema con un beneficio
        -- Basado en el formulario anterior:
        -- INFORMAR_UN_PROBLEMA_CON_UN_BENEFICIO_OTORGADO
        -- ------------------------------------------------------------

        (
            'INFORMAR_UN_PROBLEMA_CON_UN_BENEFICIO',
            'numeroSolicitud',
            'Número de solicitud o referencia',
            'TEXT',
            FALSE,
            1,
            '{"placeholder":"Si lo tenés"}'
        ),

        (
            'INFORMAR_UN_PROBLEMA_CON_UN_BENEFICIO',
            'tipoProblema',
            'Tipo de problema',
            'SELECT',
            TRUE,
            2,
            '{
                "placeholder":"Seleccionar...",
                "options":[
                    {"value":"DEMORA","label":"Demora"},
                    {"value":"DOCUMENTACION","label":"Documentación"},
                    {"value":"ACREDITACION","label":"Acreditación"},
                    {"value":"DATOS","label":"Datos incorrectos"},
                    {"value":"OTRO","label":"Otro"}
                ]
            }'
        ),


        -- ------------------------------------------------------------
        -- Informar un problema con alguna documentación
        -- Basado en el patrón existente de problemas de documentación
        -- ------------------------------------------------------------

        (
            'INFORMAR_UN_PROBLEMA_CON_ALGUNA_DOCUMENTACION',
            'tipoProblema',
            'Tipo de problema',
            'SELECT',
            TRUE,
            1,
            '{
                "placeholder":"Seleccionar...",
                "options":[
                    {"value":"FUNCIONAMIENTO","label":"Funcionamiento"},
                    {"value":"DEMORA","label":"Demora"},
                    {"value":"DANO","label":"Daño"},
                    {"value":"INCUMPLIMIENTO","label":"Incumplimiento"},
                    {"value":"OTRO","label":"Otro"}
                ]
            }'
        ),

        (
            'INFORMAR_UN_PROBLEMA_CON_ALGUNA_DOCUMENTACION',
            'referenciaAdicional',
            'Referencia adicional',
            'TEXT',
            FALSE,
            2,
            '{"placeholder":"Número, identificación o dato específico si corresponde"}'
        )
)

INSERT INTO form_fields (
    form_template_id,
    code,
    label,
    type,
    required,
    display_order,
    config
)
SELECT
    ft.id,
    seed.field_code,
    seed.label,
    seed.field_type,
    seed.required,
    seed.display_order,
    seed.config::jsonb
FROM seed
         JOIN request_types rt
              ON rt.code = seed.request_type_code
         JOIN form_templates ft
              ON ft.request_type_id = rt.id
                  AND ft.version = 1
    ON CONFLICT (form_template_id, code) DO UPDATE SET
    label = EXCLUDED.label,
                                                type = EXCLUDED.type,
                                                required = EXCLUDED.required,
                                                display_order = EXCLUDED.display_order,
                                                config = EXCLUDED.config;

-- ================================================================
-- LIMPIEZA DE REQUEST TYPES RETIRADOS SIN REFERENCIAS HISTÓRICAS
-- ================================================================
-- Si un Request Type retirado no tiene tickets asociados, se eliminan primero
-- sus FormField, luego sus FormTemplate y finalmente el RequestType.
-- Si tiene tickets asociados, se conserva con active=FALSE.
-- ================================================================

CREATE TEMP TABLE retired_request_type_codes (
    code VARCHAR(150) PRIMARY KEY
) ON COMMIT DROP;

INSERT INTO retired_request_type_codes (code)
VALUES
        ('CONSULTAR_COMO_DISPONER_RESIDUOS_ESPECIALES'),
        ('CONSULTAR_PUNTOS_VERDES'),
        ('CONSULTAR_REQUISITOS_DE_HABILITACION'),
        ('CONSULTAR_ESTADO_DE_UNA_HABILITACION'),
        ('CONSULTAR_UNA_INSPECCION_PROGRAMADA'),
        ('CONSULTAR_UNA_INTIMACION_O_CLAUSURA'),
        ('CONSULTAR_ZONAS_HORARIOS_O_TARIFAS'),
        ('CONSULTAR_UN_CORTE_DE_CALLE'),
        ('CONSULTAR_UNA_INFRACCION'),
        ('CONSULTAR_COMO_PRESENTAR_UN_DESCARGO'),
        ('CONSULTAR_REQUISITOS_DE_LIBERACION'),
        ('CONSULTAR_ESTADO_DE_UN_VEHICULO_RETENIDO'),
        ('CONSULTAR_UNA_BOLETA_MUNICIPAL'),
        ('CONSULTAR_DEUDA_MUNICIPAL'),
        ('CONSULTAR_OPCIONES_DE_FINANCIACION'),
        ('CONSULTAR_REQUISITOS_DE_UNA_EXENCION'),
        ('CONSULTAR_ESTADO_DE_UNA_SOLICITUD_DE_EXENCION'),
        ('CONSULTAR_PROGRAMAS_DISPONIBLES'),
        ('CONSULTAR_REQUISITOS_DE_UN_BENEFICIO'),
        ('CONSULTAR_ESTADO_DE_UNA_SOLICITUD'),
        ('INFORMAR_UN_PROBLEMA_CON_LA_DOCUMENTACION_DESARROLLO_SOCIAL'),
        ('RECLAMAR_POR_UNA_DEMORA_EN_LA_EVALUACION'),
        ('INFORMAR_UN_PROBLEMA_CON_UN_BENEFICIO_OTORGADO'),
        ('CONSULTAR_UNA_VISITA_PROGRAMADA'),
        ('CONSULTAR_DISPONIBILIDAD_DE_TURNOS'),
        ('CONSULTAR_CAMPANAS_COMUNITARIAS'),
        ('CONSULTAR_UNA_REPRESENTACION'),
        ('CONSULTAR_COMO_REGISTRARSE'),
        ('CONSULTAR_ESTADO_DE_UN_EXPEDIENTE'),
        ('CONSULTAR_DOCUMENTACION_REQUERIDA'),
        ('CONSULTAR_EL_AREA_RESPONSABLE');

-- Eliminar FormField de Request Types retirados que no tengan tickets asociados.
DELETE FROM form_fields ff
USING form_templates ft, request_types rt, retired_request_type_codes retired
WHERE ff.form_template_id = ft.id
  AND ft.request_type_id = rt.id
  AND retired.code = rt.code
  AND NOT EXISTS (
      SELECT 1
      FROM tickets t
      WHERE t.request_type_id = rt.id
  );

-- Eliminar FormTemplate de Request Types retirados que no tengan tickets asociados.
DELETE FROM form_templates ft
USING request_types rt, retired_request_type_codes retired
WHERE ft.request_type_id = rt.id
  AND retired.code = rt.code
  AND NOT EXISTS (
      SELECT 1
      FROM tickets t
      WHERE t.request_type_id = rt.id
  );

-- Eliminar finalmente los Request Types retirados que no tengan tickets asociados.
DELETE FROM request_types rt
USING retired_request_type_codes retired
WHERE retired.code = rt.code
  AND NOT EXISTS (
      SELECT 1
      FROM tickets t
      WHERE t.request_type_id = rt.id
  );

-- ================================================================
-- VERIFICACIONES MANUALES SUGERIDAS
-- ================================================================

-- En una base limpia deberían existir exactamente 14 categorías activas.
-- SELECT COUNT(*) FROM categories WHERE active = TRUE;
-- Esperado: 14

-- En una base limpia deberían existir exactamente 52 subcategorías activas.
-- SELECT COUNT(*)
-- FROM subcategories s
-- JOIN categories c ON c.id = s.category_id
-- WHERE s.active = TRUE AND c.active = TRUE;
-- Esperado: 52

-- En una base limpia deberían existir exactamente 110 Request Types totales y activos.
-- SELECT COUNT(*) FROM request_types;
-- Esperado: 110

-- SELECT COUNT(*) FROM request_types WHERE active = TRUE;
-- Esperado: 110

-- Los 2 Request Types nuevos deben tener un FormTemplate activo cada uno.
-- SELECT COUNT(*)
-- FROM form_templates ft
-- JOIN request_types rt ON rt.id = ft.request_type_id
-- WHERE rt.code IN (
--     'INFORMAR_UN_PROBLEMA_CON_UN_BENEFICIO',
--     'INFORMAR_UN_PROBLEMA_CON_ALGUNA_DOCUMENTACION'
-- )
-- AND ft.active = TRUE;
-- Esperado: 2

-- Ambos Request Types nuevos deben tener 2 FormField cada uno.
-- SELECT rt.code, COUNT(ff.id) AS fields
-- FROM request_types rt
-- JOIN form_templates ft ON ft.request_type_id = rt.id
-- LEFT JOIN form_fields ff ON ff.form_template_id = ft.id
-- WHERE rt.code IN (
--     'INFORMAR_UN_PROBLEMA_CON_UN_BENEFICIO',
--     'INFORMAR_UN_PROBLEMA_CON_ALGUNA_DOCUMENTACION'
-- )
-- AND ft.active = TRUE
-- GROUP BY rt.code
-- ORDER BY rt.code;
-- Esperado: 2 filas, ambas con fields = 2.

