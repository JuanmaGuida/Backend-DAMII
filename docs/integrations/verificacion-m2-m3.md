# Entrega local M2 ↔ M3

Rama `dev`; HEAD `2911715a93b748d0919446803e0ab7249fa5005f`.
El árbol estaba limpio al iniciar. No se crearon ramas, commits, pushes, merges ni PRs.
Todos los cambios permanecen sin commitear. No se tocaron identidad, JWT, ciudadanos,
SecurityConfiguration, CorePublicationPolicy, OutgoingEventValidator ni los schemas v1.78.

## Resultado

Se admiten referencias externas transaccionales y PROGRESS sólo con evidencias; se
registran fotografías de RESOLVED y se deduplican por Inbox/eventId. Se distinguen
rechazos definitivos auditados/ACK de fallos con NACK; no se confirma antes del commit.
Se protege contra hechos anteriores al estado actual. Se reutilizan las transiciones,
SLA, cancelación con duplicados y publicación existentes, sin nuevos eventos ni espejos.
Nuevo endpoint de metadatos autorizado, sin descarga de URLs y sin caché.
La migración V51 crea una tabla separada; no modifica migraciones previas ni configuración
Rabbit. OpenAPI sólo agrega el endpoint local de consulta.

## Pruebas reales ejecutadas

Entorno: JDK Temurin 21.0.13, Maven 3.9.16, PostgreSQL 17 en contenedor exclusivo de tests;
fixtures CI del repositorio, integraciones remotas deshabilitadas.

- Reportes finales: **1712 pruebas contabilizadas, 1707 aprobadas, 5 omitidas, 0 fallos, 0 errores**, en 163 suites.
- Cobertura JaCoCo LINE: **96.73%** (6058/6263 líneas); gate del 85% aprobado.
- Tests específicos iniciales: 117/117 aprobados; segunda tanda ampliada: 75/75.
- Se ejecutó la suite completa mediante `/workspace/.cloud-setup/mvn clean verify`.
  Detectó expectativas OpenAPI desactualizadas por el endpoint nuevo. Se actualizaron
  el inventario de operaciones y el conteo de rutas, manteniendo la prueba de respuestas
  existentes. No se desactivó ninguna aserción.
- Reejecución final: `/workspace/.cloud-setup/mvn -Dtest=OpenApiContractTest,ExternalAttachmentControllerTest verify`:
  **20/20 aprobados, BUILD SUCCESS**, empaquetado y gate de cobertura aprobados.
  Los totales anteriores corresponden a los reportes de la suite completa con los tests
  afectados reejecutados; no se presenta la ejecución previa fallida como un clean verify exitoso.
- Las cinco omitidas pertenecen a `Clase11SecurityAcceptanceTest`, opt-in existente que
  requiere `clase11.security.enabled=true` y una base vacía. No se cambiaron sus condiciones.
- Regresión ejecutada de M1/identidad, seguridad, simulador, Inbox/Outbox, SLA, migraciones,
  reclasificación, cancelación, notificaciones y adjuntos locales. Sin fallos pendientes.
- Nuevas pruebas verifican las seis variantes M3 con evidencias, resolución desde ambos
  estados, respuesta ciudadana/reanudación SLA, no espejo, deduplicación, área incorrecta,
  hechos antiguos, rollback de referencias y resolución, auditoría de rechazo y ACK/NACK,
  permisos y no-store del endpoint, y los cuatro ejemplos JSON contra schemas locales.
- `git diff --check` sin errores.

## Inventario de archivos

Modificados (12):

- `src/main/java/com/reclamos/backend/dto/UpdateTicketStatusRequest.java`
- `src/main/java/com/reclamos/backend/inbox/CoreEventConsumer.java`
- `src/main/java/com/reclamos/backend/inbox/CoreInboxProcessor.java`
- `src/main/java/com/reclamos/backend/inbox/CoreIncomingEventValidator.java`
- `src/main/java/com/reclamos/backend/inbox/IncomingEventFailure.java`
- `src/main/java/com/reclamos/backend/service/TicketStatusUpdateService.java`
- `src/main/resources/static/openapi.yaml`
- `src/test/java/com/reclamos/backend/inbox/CoreEventConsumerTest.java`
- `src/test/java/com/reclamos/backend/inbox/CoreInboxPostgresIntegrationTest.java`
- `src/test/java/com/reclamos/backend/openapi/OpenApiContractTest.java`
- `src/test/java/com/reclamos/backend/service/TicketOutboxServiceTest.java`
- `src/test/java/com/reclamos/backend/service/TicketStatusUpdateServiceTest.java`

Creados (14):

- `docs/integrations/examples/information-required.json`
- `docs/integrations/examples/resolved.json`
- `docs/integrations/examples/routed.json`
- `docs/integrations/examples/started.json`
- `docs/integrations/m2-m3.md`
- `docs/integrations/verificacion-m2-m3.md`
- `src/main/java/com/reclamos/backend/controller/ExternalAttachmentController.java`
- `src/main/java/com/reclamos/backend/entity/ExternalAttachmentReference.java`
- `src/main/java/com/reclamos/backend/repository/ExternalAttachmentReferenceRepository.java`
- `src/main/java/com/reclamos/backend/service/ExternalAttachmentService.java`
- `src/main/resources/db/migration/V51__external_attachment_references.sql`
- `src/test/java/com/reclamos/backend/controller/ExternalAttachmentControllerTest.java`
- `src/test/java/com/reclamos/backend/service/ExternalAttachmentServiceTest.java`
- `src/test/java/com/reclamos/backend/service/M3DocumentationContractTest.java`

Eliminados: ninguno.

## Pendientes externos

Ver [guía bilateral](m2-m3.md). No se realizó conexión, publicación ni homologación real
con Core/M3: faltan credenciales autorizadas, suscripciones/topología y política retry/DLQ
verificadas, y los documentos completos M2 v1.79 y M3 v0.7. Las fixtures son sintéticas.
El schema no define visibilidad de evidencias: se conservan INTERNAL hasta acuerdo bilateral;
no se habilita exposición ciudadana ni se supone autorización sobre URLs de M3.
