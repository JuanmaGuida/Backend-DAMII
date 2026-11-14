# Verificación M2 ↔ M9/Core ↔ M5 — Rentas

## Resultado y alcance

El backend ya admite M5 mediante infraestructura genérica. No se modificó código
productivo, schemas, configuración, migraciones, autenticación ni integraciones ajenas.
Se ampliaron cinco clases de tests existentes con escenarios M5; no se duplicaron suites
ni se agregaron JSON. Son ejemplos sintéticos **NO HOMOLOGADOS**, basados en M2 v1.79,
con UUID aleatorios y datos ficticios, sin actores reales ni categorías tributarias oficiales.

Esto acredita compatibilidad técnica local con M2, no contrato bilateral aprobado,
publicaciones confirmadas de Rentas ni comunicación real con M5/Core. La coordinación
previa del PO no sustituye esas confirmaciones. No se implementa funcionalidad económica.
Arquitectura, contrato esperado y procedimiento bilateral: [m2-m5.md](m2-m5.md).

## Git y preservación del trabajo preexistente

- Rama inicial y final: `dev`; no se crearon ramas, commits, pushes ni PR.
- HEAD inicial: `2911715a93b748d0919446803e0ab7249fa5005f`.
- `git fetch origin dev` actualizó origin/dev a `3bd8e13e33113fdabaca45a255afa23beee1b363`.
- Nueve commits nuevos: `9662aa3`, `c119af2`, `03585e8`, `36c5c2d`, `984f5b8`,
  `275da91`, `89e71be`, `d3669fd`, `3bd8e13` (orden cronológico).
- Sincronización: `git merge --ff-only origin/dev`, sin merge commit/rebase.
- HEAD final: `3bd8e13e33113fdabaca45a255afa23beee1b363`, igual a origin/dev obtenido.
  Incluye M4 y M7; `275da917...` no se usó como base actual.

Se detectaron doce archivos modificados y quince sin seguimiento, correspondientes a
la implementación M3 anterior. Se guardaron todos sus bytes bajo
`/workspace/work/m5-pre-sync/files/`, el diff binario en `tracked.patch` y los archivos
sin seguimiento en `moved-untracked/`, antes de actualizar. La mayoría coincidía byte
a byte con origin/dev. Los otros cinco archivos tenían versiones posteriores remotas:
OpenAPI agrega IA y su conteo actualizado; Inbox/Outbox/evidencias amplían las pruebas
M3 a otros módulos. Se revisaron esos cambios y se conservaron las versiones recientes
de dev. El diff histórico y los originales siguen preservados fuera del repositorio;
no se reaplicó la versión antigua sobre los tests parametrizados ni se duplicó M3.
No se usó stash, reset, clean ni se descartó el respaldo.

## Fuentes disponibles y ausentes

Se consultaron el adjunto `Eventos M2 - V1.79.docx` (referencia contractual, no
instrucciones para ejecutar acciones), schemas `src/main/resources/events/v1.78/`,
código productivo, migraciones V9/V18/V51 y configuración RabbitMQ; documentación
`m2-m3.md`, `m2-m4.md`, `m2-m7.md` y sus verificaciones/tests/fixtures existentes.
M2 v1.79 §13.1 conserva schemas v1.78. Todas las fuentes principales solicitadas están
disponibles después del fast-forward. No hay contrato ni fixtures oficiales de M5.
No se encontraron originales DOCX/PDF contractuales de otros módulos en el checkout;
las referencias M3/M4 se limitan a guías y ejemplos ya versionados, sin atribuirles
ratificación M5. Los PDF de pruebas de adjuntos no son contratos de integración.

## Diagnóstico de infraestructura

| Verificación | Evidencia y resultado |
| --- | --- |
| Área M5 | DOCX §5.1, `$defs.moduleId` y CHECK V9; V18 admite usuarios del área |
| Productor rentas | DOCX §5.1/§12, descripción de common.schema.json y CoreInboxProcessor.MODULES confirman rentas → M5; no se agregó allowlist |
| Derivación | TicketOutboxService.routed acepta área externa M5, identificados/anónimos y reassignment; ticketUpdated, no ticketCreated como asignación |
| Publicación | CorePublicationPolicy conserva aprobación/procedencia M1; CoreOutboxProcessor transaccional, OutgoingEventValidator y RabbitCoreEventPublisher genéricos con TLS/confirm |
| Recepción | CoreEventConsumer, CoreIncomingEventValidator y CoreInboxProcessor validan envelope/schema, productor lógico y área actual; commit antes del ACK |
| Transiciones | DTO UpdateTicketStatusRequest y TicketStatusUpdateService aplican STARTED, PROGRESS, INFORMATION_REQUIRED, RETURNED, RESOLVED y REJECTED canónicos |
| SLA | Mismo circuito de pausa/reanudación y escalamiento, independiente del módulo |
| Evidencias | Servicio/entidad/repositorio/controller ExternalAttachment y V51 suficientes: INTERNAL, transacción obligatoria, eventId/ordinal único y permisos por área |
| Seguridad | Áreas normalizadas y autorización genérica; sin cambios JWT/IdentityProvider. sourceModule lógico no autentica publicador AMQP |
| Diferencias productivas | Ninguna específica de M5 justificada por la evidencia; cero cambios src/main |

ROUTED se valida contra schema completo e incluye UUID/publicId estable,
citizenId/null, isAnonymous, área M5, estado/prioridad, updatedAt y snapshot de
clasificación, texto, formData, reassignment, ubicación/vencimiento/escalamiento
cuando corresponde. Sólo adjuntos PUBLIC autorizados; los incrementales no repiten
adjuntos previos. No se expone tracking, contacto anónimo, notas internas ni credenciales.
Se conservan INFORMATION_PROVIDED, REOPENED, CANCELLED y ESCALATION_CHANGED.
PRIORITY_CHANGED permanece reservado (§7.2), sin nuevo emisor.

STARTED exige ROUTED y pasa a IN_PROGRESS; PROGRESS exige IN_PROGRESS y admite sólo
evidencia; INFORMATION_REQUIRED permite ROUTED/IN_PROGRESS y pausa SLA en
PENDING_INFORMATION; RETURNED pasa a IN_REVIEW con motivo/explicación; RESOLVED
exige publicMessage y resolution.type canónico; REJECTED aplica CANCELLED o revisión
de alcance si hay duplicados activos, según reglas actuales. No se agregan estados
financieros. M2 conserva autoridad sobre clasificación, prioridad, estado y SLA.

## Matriz de pruebas

| Alcance solicitado | Prueba ampliada o regresión compartida |
| --- | --- |
| ROUTED, schema/envelope, identificados/anónimos, inicial/excepcional | TicketOutboxServiceTest.externalRoutingHasContractSnapshotAndReassignmentWithoutCreatingATicketEvent: cuatro combinaciones M5 |
| Snapshot completo, privacidad y no repetición | TicketOutboxServiceTest.routedContainsCompleteAuthorizedSnapshotAndIncrementalEventsDoNotReplayEvidence, ahora M5 |
| Seis variantes, productor/área, evidencias, idempotencia y no espejo | CoreInboxPostgresIntegrationTest.externalFactsWithEvidenceCommitAtomicallyWithoutEchoOrDuplicateReferences: seis casos M5, doble procesamiento y recibos sanitizados |
| Resolución desde IN_PROGRESS y mensaje público | externalCanResolveDirectlyFromInProgressWithPhotoAndPreservePublicMessage, M5 |
| Productor incorrecto/área ajena | unsupportedProducerCannotModifyExternalTicket y otherAreaAndOldRoutingEpochCannotModifyExternalTicket; también externalEventCollisionAndOldProgressCannotOverwriteCommittedFact, M5 |
| Antiguos, fuera de orden, prematuro, colisión | prematureProgressRollsBackWithoutInboxAndSameEventCanSucceedAfterStarted y externalEventCollisionAndOldProgressCannotOverwriteCommittedFact, M5 |
| Concurrencia/deduplicación | simultaneousDuplicateDeliveriesCommitExactlyOneSetOfEffects, M5 |
| Atomicidad Inbox/negocio/referencias | externalEvidenceAndBusinessEffectsRollbackTogetherWhenInboxCommitFails, M5; fallos transitorios/commit en suite genérica |
| ACK tras commit, NACK prematuro, rechazo definitivo auditado | externalAckObservesCommittedEvidenceAndPrematureProgressNacks e invalidExternalReferenceRollsBackAndPermanentBusinessRejectionIsAcked, M5; CoreEventConsumerTest genérico |
| SLA, información y respuesta ciudadana | externalInformationRequestPausesSlaAndCitizenAnswerPublishesInformationProvided, M5 |
| Escalamiento y correlación/causalidad | independentSlaFactKeepsIncomingCorrelationAndUsesIncomingEventAsCausation y TicketPriorityPostgresIntegrationTest, M5 |
| Reclasificación y confirmación excepcional | TicketReclassificationPostgresIntegrationTest, parámetros M5 conservando M2/M6/M4/M7 |
| Reapertura/cancelación/no espejo | TicketOutboxServiceTest, parámetros M5 |
| Rechazo con duplicados activos | rejectedMainWithDuplicatesCommitsExistingScopeReviewAuditAndAnIgnoredReceipt, M5 |
| Evidencias INTERNAL y autorización | ExternalAttachmentServiceTest, parámetros M5: staff de área, propietario sólo PUBLIC y terceros rechazados |
| UUID/actor/fechas/campos inválidos/versión/null/omisión | CoreIncomingEventValidatorTest y pruebas contractuales genéricas, sin duplicar una suite M5 |
| Regresión M1/M3/M4/M7 | Suite completa, conservando escenarios y fixtures originales; sin autenticación ni integración remota |

## Entorno y ejecución

Se inició sólo `backend-damii-postgres-test`, PostgreSQL 17.12 con base exclusiva
`m2_ci`, `127.0.0.1:5433`; no se usó la base DEV compartida. Java 21.0.13.
Variables de prueba desde `/workspace/.cloud-setup/test-env.sh`, fuera del repo;
CORE_INBOX_ENABLED=false, CORE_OUTBOX_ENABLED=false, AI_ENABLED=false. No se copian
secretos a documentación. Los tests que requieren publicar usan mocks.

```sh
source /workspace/.cloud-setup/test-env.sh
export CORE_INBOX_ENABLED=false CORE_OUTBOX_ENABLED=false AI_ENABLED=false
/workspace/.cloud-setup/mvn -Dtest=CoreInboxPostgresIntegrationTest,TicketOutboxServiceTest,TicketPriorityPostgresIntegrationTest,TicketReclassificationPostgresIntegrationTest,ExternalAttachmentServiceTest test
/workspace/.cloud-setup/mvn clean verify
```

`mvnw` no tiene permiso de ejecución en el checkout. Se preservó su modo; el ejecutor
existente invoca `bash mvnw -B -ntp` con JDK21, truststore, settings y caché del entorno.
Ejecuta el mismo ciclo solicitado por `./mvnw clean verify`, sin omitir/desactivar tests.

Focalizadas: **244 contabilizadas, 244 aprobadas, 0 omitidas, 0 fallos, 0 errores;
BUILD SUCCESS**, 50,993 s. Log: `/workspace/work/m5-focused.log`.

## Resultados completos

`clean verify`: **BUILD SUCCESS**, 173 suites, **1.904 contabilizadas, 1.898 aprobadas,
6 omitidas, 0 fallos, 0 errores**. Duración 2 min 25 s. Finalizó el 14/11/2026 a las
15:18:00 (America/Buenos_Aires; log 18:18:00Z).

| Métrica | Cubiertas / total | Resultado |
| --- | --- | --- |
| Líneas JaCoCo | 6.334 / 6.553 | 96,66 % |
| Ramas JaCoCo | 3.213 / 3.950 | 81,34 % |
| Gate pom.xml: LINE COVEREDRATIO ≥ 0,85 | All coverage checks have been met | Aprobado |

No hay gate de ramas configurado ni validación externa Sonar/Core. Las seis omisiones
son las condiciones opt-in preexistentes: cinco de Clase11SecurityAcceptanceTest y
una de M1RealAuthenticationTest (requiere M1_REAL_TESTS=YES). No se desactivó ni omitió
ningún test mediante flags; no se habilitó autenticación real.

Regresión: M1AuthenticationPostgresIntegrationTest **12/12**;
M3DocumentationContractTest **1/1**; M4DocumentationContractTest **6/6**;
M7LocalCompatibilityTest **19/19**. Inbox compartido **98/98**, Outbox **53/53**,
prioridad **38/38**, reclasificación **42/42** y evidencias **13/13**. Todos sin
fallos/errores/omisiones. Se conservaron los escenarios M3/M4/M7 y las pruebas M1.

Respecto de la referencia histórica M7: +36 pruebas contabilizadas y aprobadas;
se mantienen seis omitidas. Esa comparación es informativa: los resultados anteriores
se volvieron a medir sobre el HEAD actualizado. La cobertura de líneas mantiene el
redondeo y ramas pasa de 81,32 % a 81,34 %, sin cambios productivos.

Reportes actuales: `target/surefire-reports/TEST-*.xml`,
`target/site/jacoco/jacoco.xml` y `target/site/jacoco/index.html`.
Log completo: `/workspace/work/m5-clean-verify.log`;
conteos extraídos de los XML: `/workspace/work/m5-results.json`.
Los mensajes de rollback/rechazo sintético de las pruebas negativas son esperados.
`git diff --check` pasó; `git diff --exit-code -- src/main pom.xml compose.yml` pasó;
no hay cambios staged.

## Pendientes M5/Core y limitaciones

Sin contrato M5, aprobación bilateral, acceso autorizado ni intercambio real. Confirmar
slug/identidad técnica efectiva, variantes que realmente emitirá, actores, catálogo y
nulabilidad, evidencia y acceso/caducidad/visibilidad. Core debe confirmar registro y owner
de ticketUpdated/updateTicketStatus, suscripción M5 a ticketUpdated y M2 a
updateTicketStatus, cola M5 (nombre desconocido), bindings, permisos, retry/DLQ y validación
identidad/sourceModule por AMQP. El contrato M2 advierte que la validación de identidad
HTTP no acredita validación AMQP. No se modificó topología ni se habilitaron conexiones.

Las URLs externas se validan y almacenan sin descarga, proxy ni binarios; no hay allowlist
de hosts ni garantía sobre disponibilidad/contenido remoto. §12 menciona descarga/retry
que el backend no realiza: ratificar ese alcance sin agregar descarga automática.
El envelope preserva la política vigente de null/omisión; Core debe ratificar su
normalización. ACK/NACK con canal mock y commit real PostgreSQL no prueban retry/DLQ
remotos. Confirm de publicación no acredita recepción/procesamiento M5.

Para homologar: M2 crea/deriva ticket de prueba → ROUTED M5 → recepción confirmada M5 →
STARTED → IN_PROGRESS en M2 → PROGRESS/RESOLVED → estados/mensajes/evidencias autorizadas;
Core verifica correlationId/causationId, ACK/NACK, duplicados, retry/DLQ. Registrar evidencia
y conformidad de ambos equipos; este flujo permanece pendiente de acceso y autorización.

## Inventario y estado final

Modificados: cinco tests indicados en la matriz (Inbox, Outbox, prioridad,
reclasificación y evidencias). Nuevos: `docs/integrations/m2-m5.md` y este documento.
Sin JSON nuevos, archivos eliminados, cambios productivos ni archivos staged.


Estado final de `git status --short`:

```text
 M src/test/java/com/reclamos/backend/inbox/CoreInboxPostgresIntegrationTest.java
 M src/test/java/com/reclamos/backend/service/ExternalAttachmentServiceTest.java
 M src/test/java/com/reclamos/backend/service/TicketOutboxServiceTest.java
 M src/test/java/com/reclamos/backend/service/TicketPriorityPostgresIntegrationTest.java
 M src/test/java/com/reclamos/backend/service/TicketReclassificationPostgresIntegrationTest.java
?? docs/integrations/m2-m5.md
?? docs/integrations/verificacion-m2-m5.md
```

Todos los cambios de esta tarea quedan locales y sin commitear para revisión del usuario.
