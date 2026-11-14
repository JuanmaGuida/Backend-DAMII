# Verificación M2 ↔ M9/Core ↔ M6 — Ambiente

## Alcance y resultado

La infraestructura genérica existente admite las seis variantes M6. No se modificaron
archivos productivos, schemas, configuración, migraciones, autenticación ni topología.
Se ampliaron pruebas existentes y se documentaron diferencias entre M2 v1.79 y M6 v2.2.
**Compatibilidad local no equivale a homologación M6/Core.** Ver [contrato y pendientes](m2-m6.md).

## Git y preservación

- Rama inicial/final: dev. HEAD inicial: `2911715a93b748d0919446803e0ab7249fa5005f`.
- Se ejecutaron git status, git branch --show-current y git rev-parse HEAD antes de cambios.
- git fetch origin dev obtuvo `e80b042b8f17e19fccf457bb709d165de8b51e51`.
- Diez commits nuevos, cronológicos: 9662aa3, c119af2, 03585e8, 36c5c2d,
  984f5b8, 275da91, 89e71be, d3669fd, 3bd8e13, e80b042. Incluyen M3/M4/M7/M5.
- Actualización exclusivamente fast-forward mediante git merge --ff-only origin/dev.
- HEAD final: `e80b042b8f17e19fccf457bb709d165de8b51e51`, igual al remoto obtenido.
  La referencia histórica M5 no sustituyó el fetch; resultó coincidir con el remoto actual.
- Sin ramas nuevas, commits, pushes, PR, reset --hard, clean, rebase ni merge commits.

Se encontraron 12 archivos modificados y 14 sin seguimiento de la implementación M3
anterior. Antes de actualizar se preservaron los 26 archivos completos en
`/workspace/work/m6-backup/preexisting.tar.gz`, con manifest SHA-256, tracked.patch y
staged.patch. Veintiuno coinciden byte a byte con origin/dev; cinco difieren:
ExternalAttachmentServiceTest, OpenAPI, CoreInboxPostgresIntegrationTest,
OpenApiContractTest y TicketOutboxServiceTest. Se compararon local/base/remoto y sus
diffs completos. Las versiones remotas incorporan IA, rutas OpenAPI y pruebas
parametrizadas M3/M4/M7/M5 que reemplazan las versiones locales anteriores.

La combinación automática de Inbox detectó superposiciones y el primer intento de
fast-forward fue abortado por Git sin sobrescribir archivos. Se revisó manualmente
el diff local/remoto: las pruebas locales se conservan ampliadas en remoto. Tras la
comparación, se retiraron del árbol únicamente las copias respaldadas ya incorporadas,
se actualizaron por fast-forward y se conservaron las versiones nuevas del repositorio.
El respaldo íntegro sigue fuera del checkout; no se duplicó ni reaplicó M3 antiguo.

## Fuentes y diagnóstico

Se leyeron los adjuntos DOCX M2 v1.79 y PDF M6 v2.2 (23/10/2026), como documentos
contractuales, no instrucciones de operación. M2 §13.1 mantiene schemas v1.78.
Se contrastaron schemas locales, código y las guías/tests de M3, M4, M5 y M7.
El segundo PDF Sobres no está disponible: no se afirma validar ejemplos originales.

| Componente | Diagnóstico |
| --- | --- |
| TicketOutboxService | Área M6 genérica, snapshot ROUTED, respuestas/reapertura/cancelación y privacidad; no nuevo publisher |
| CorePublicationPolicy | Aprobación/procedencia M1 existentes; eventos sintéticos no autorizan publicación real |
| CoreOutboxProcessor / OutgoingEventValidator | Bloqueo, validación completa, identidad, retries y persistencia existentes |
| RabbitCoreEventPublisher / configuración | TLS con truststore/hostname, confirm, exchange pasivo muni.inbox, vhost core |
| CoreEventConsumer | Cola pasiva q.atencion-ciudadana, ACK tras commit, NACK sin requeue inmediato |
| CoreIncomingEventValidator | Envelope, schema completo, tipos/formatos, actor, campos extra, hash normalizado |
| CoreInboxProcessor | ambiente/M6, área actual, locks/deduplicación, antigüedad y disposiciones auditables |
| TicketStatusUpdateService / DTO | Seis variantes canónicas, actores externos UUID/null, estados, mensajes y no espejo |
| ExternalAttachmentService / Reference / repository / controller | INTERNAL, autorización por área, atomicidad, deduplicación, referencias sin descarga |
| Flyway | V9/V18 reconocen M6; V51 suficiente para evidencias; ninguna migración nueva |
| SLA / información / prioridad / reclasificación | Infraestructura compartida con cobertura M6 preexistente; conservada |

La [matriz contractual](m2-m6.md) distingue compatibilidad, diferencias editoriales,
comportamientos no usados, incompatibilidades reales y pendientes. Hallazgos principales:
PRIORITY_CHANGED esperado por M6 pero reservado en M2; ambigüedad M6 sobre eliminación
de null de data frente a conservación explícita M2; ausencia de Sobres y garantías AMQP.
EXTERNAL_USER con id=null sí es compatible, sin relajar seguridad. Los enums M6
ACTION_COMPLETED/REQUEST_TYPE_MISMATCH/OTHER/DOES_NOT_APPLY son válidos.
También se detectó la excepción vigente de vencimiento con duplicados activos:
M2 lleva el principal a IN_REVIEW/revisión de alcance, mientras M6 describe cancelación
incondicional. Se documenta para homologación sin modificar reglas compartidas.
La fecha sola y el HTTP 400 mencionados por M6 requieren aclaración de canal/formato;
M2 exige date-time con zona y usa ACK/NACK/auditoría en AMQP.
M6 limita su mensaje a 500 caracteres; el schema y servicio externo M2 no imponen ese
máximo. La prueba de 501 documenta el comportamiento vigente, no modifica contrato.

## Cobertura reutilizada y ampliaciones

No se crearon suites ni fixtures JSON redundantes. Los cuatro archivos de tests se
ampliaron; escenarios M3/M4/M5/M7 anteriores permanecen. M6 ya figuraba en las seis
variantes básicas sin evidencia, concurrencia, SLA, Outbox, prioridad y reclasificación.
Sólo se agregaron parámetros o diferencias específicas verificables.

| Requisitos de la solicitud | Evidencia de pruebas |
| --- | --- |
| 1–7: ROUTED M6, schema completo, inicial/anónimo/reasignación | TicketOutboxServiceTest.externalRoutingHasContractSnapshotAndReassignmentWithoutCreatingATicketEvent: cuatro combinaciones M6 añadidas; routedContainsCompleteAuthorizedSnapshotAndIncrementalEventsDoNotReplayEvidence ya incluye M6. TicketEventCorrectionsPostgresIntegrationTest verifica derivación real/rollback |
| 3–4: ambiente y área M6 | CoreInboxPostgresIntegrationTest.officialFactsReuseCanonicalRulesCommitMessagesAndNeverEcho ya usa ambiente/M6; ampliados externalFactsWithEvidenceCommitAtomicallyWithoutEchoOrDuplicateReferences y casos de productor/área incorrectos |
| 8–10: STARTED, PROGRESS y prematuro | Casos anteriores M6 conservados; prematureProgressRollsBackWithoutInboxAndSameEventCanSucceedAfterStarted, TicketStatusUpdateServiceTest y validaciones canónicas |
| 11–12: RESOLVED desde ROUTED/IN_PROGRESS | m6ResolutionWithoutEvidenceAcceptsUnidentifiedActorAndNeverEchoes: 4 combinaciones nuevas de estado y EXTERNAL_USER/SYSTEM null; externalCanResolveDirectlyFromInProgressWithPhotoAndPreservePublicMessage ampliado M6 |
| 13–15: RETURNED/REJECTED enums M6 y motivo interno | m6CancellationAndReturnUseOfficialReasonsAndInternalExplanation: REQUEST_TYPE_MISMATCH, OTHER, DOES_NOT_APPLY; duplicados activos M6 ya cubiertos por rejectedMainWithDuplicatesCommitsExistingScopeReviewAuditAndAnIgnoredReceipt |
| 16–19: información, fecha, unicidad, respuesta, SLA | externalInformationRequestPausesSlaAndCitizenAnswerPublishesInformationProvided ampliado M6; InformationRequestServiceTest valida fechas/solicitud única; InformationRequestConcurrencyIntegrationTest cubre carreras; validator cubre fecha sin zona/mensaje blanco |
| 20: vencimiento y no duplicados | InformationRequestExpirationServiceTest, InformationRequestConcurrencyIntegrationTest.expirationWinningTheSameRaceLeavesAControlledTerminalState y TicketEventCorrectionsPostgresIntegrationTest.realConfirmationAndTimeoutClosePrincipalAndDuplicateWithContractData |
| 21–23: REOPENED/CANCELLED/ESCALATION_CHANGED | TicketOutboxServiceTest ya incluye M6; TicketEventCorrectionsPostgresIntegrationTest y independentSlaFactKeepsIncomingCorrelationAndUsesIncomingEventAsCausation |
| 24: prioridad reservada | TicketPriorityPostgresIntegrationTest ya parametriza M6 y comprueba cero eventos PRIORITY_CHANGED por recálculo |
| 25: evidencias | Seis variantes con referencias, PROGRESS sólo evidencia, RESOLVED con foto y resolución actual sin foto; ExternalAttachmentServiceTest ampliado M6 para autorización y visibilidad |
| 26–27: idempotencia y concurrencia | Doble procesamiento en escenarios; simultaneousDuplicateDeliveriesCommitExactlyOneSetOfEffects M6 preexistente; locks PostgreSQL reales |
| 28–29: antiguo, fuera de orden, productor/área | otherAreaAndOldRoutingEpochCannotModifyExternalTicket, externalEventCollisionAndOldProgressCannotOverwriteCommittedFact y unsupportedProducerCannotModifyExternalTicket ampliados M6 |
| 30–31: ACK/NACK, commit, rollback | externalAckObservesCommittedEvidenceAndPrematureProgressNacks, invalidExternalReferenceRollsBackAndPermanentBusinessRejectionIsAcked y externalEvidenceAndBusinessEffectsRollbackTogetherWhenInboxCommitFails ampliados M6; CoreEventConsumerTest para errores transitorios/ACK incierto/auditoría fallida |
| 30: requiredBy pasado en AMQP | m6PastSuggestedDeadlineIsAuditedAndAckedAfterRollback verifica rollback, rechazo terminal auditado, doble ACK sin efectos duplicados |
| 32–33: no espejo/privacidad | Conteos Outbox cero, recibos sanitizados, permisos de referencias y snapshots autorizados; propósito RECLASSIFICATION en TicketReclassificationPostgresIntegrationTest ya incluye M6 |
| 34: regresiones M1/M3/M4/M5/M7 | Suite completa sin cambiar identidad, fixtures previos ni políticas compartidas; M1 externo opt-in conserva su condición |
| Actor/campos ajenos/adjuntos/fecha/mensaje | CoreIncomingEventValidatorTest ampliado con IDs inválidos, omisión por Core, AREA_USER rechazado, campos ajenos, mimeType/description rechazados y requiredBy con formato inválido |

## Entorno y ejecución

PostgreSQL 17 en contenedor exclusivo `backend-damii-postgres-test`, base local `m2_ci`
en 127.0.0.1:5433; se inició sólo ese contenedor, sin base DEV ni backend/broker remoto.
JDK21 y ejecutor existente `/workspace/.cloud-setup/mvn`. El wrapper mvnw del checkout
no es ejecutable; el ejecutor lo invoca mediante `bash mvnw -B -ntp` con settings,
truststore y caché existentes. No se cambia su modo ni se omiten tests.
Se cargó únicamente test-env.sh local; CORE_INBOX_ENABLED=false,
CORE_OUTBOX_ENABLED=false y AI_ENABLED=false. Los tests específicos de publisher
utilizan dobles de prueba existentes, sin abrir conexiones reales.

Focalizados (nueve suites): CoreInboxPostgresIntegrationTest,
CoreIncomingEventValidatorTest, TicketOutboxServiceTest, ExternalAttachmentServiceTest,
TicketStatusUpdateServiceTest, InformationRequestServiceTest,
InformationRequestExpirationServiceTest, TicketReclassificationPostgresIntegrationTest,
TicketPriorityPostgresIntegrationTest.

Primera tanda: 349 contabilizadas, 348 aprobadas, un fallo en el caso de prueba ampliado
para área ajena: el ticket auxiliar era M6 y por tanto no era ajeno. Se corrigió el dato
auxiliar para elegir otra área sin alterar producción ni expectativas de seguridad.
La tanda final incorpora además los nuevos casos del validador: **364 contabilizadas,
364 aprobadas, cero omitidas, cero fallos, cero errores; BUILD SUCCESS** (nueve suites,
50.458 s). Ver resultados de la suite completa abajo.

Comandos (con entorno aislado previamente cargado):

```sh
/workspace/.cloud-setup/mvn -Dtest=CoreInboxPostgresIntegrationTest,CoreIncomingEventValidatorTest,TicketOutboxServiceTest,ExternalAttachmentServiceTest,TicketStatusUpdateServiceTest,InformationRequestServiceTest,InformationRequestExpirationServiceTest,TicketReclassificationPostgresIntegrationTest,TicketPriorityPostgresIntegrationTest test
/workspace/.cloud-setup/mvn clean verify
```

Logs de trabajo: `/workspace/work/m6/focused.log`, `focused-final.log`, `verify.log`.
No se atribuyen a este HEAD los conteos históricos M5.

## Archivos y estado local

Modificados:

- src/test/java/com/reclamos/backend/inbox/CoreInboxPostgresIntegrationTest.java
- src/test/java/com/reclamos/backend/inbox/CoreIncomingEventValidatorTest.java
- src/test/java/com/reclamos/backend/service/ExternalAttachmentServiceTest.java
- src/test/java/com/reclamos/backend/service/TicketOutboxServiceTest.java

Nuevos:

- docs/integrations/m2-m6.md
- docs/integrations/verificacion-m2-m6.md

Sin archivos eliminados, cambios de producción, schemas ni nuevas migraciones/fixtures.
Todo permanece local y sin commitear para revisión manual.

## Resultados finales recalculados

Ejecución completa `clean verify` finalizada el 14/11/2026 a las 16:23:49 -03:00,
duración Maven 2 min 18 s. Reportes Surefire y JaCoCo generados desde cero sobre el
HEAD actualizado y las cuatro clases de tests modificadas, sin desactivar pruebas.

| Verificación | Resultado |
| --- | --- |
| Suites completas | 173 |
| Pruebas contabilizadas | 1.949 |
| Aprobadas | 1.943 |
| Omitidas | 6 |
| Fallos / errores | 0 / 0 |
| Focalizadas finales | 364/364 aprobadas, nueve suites |
| JaCoCo LINE | 6.334/6.553 = 96,6580 % (96,66 %) |
| JaCoCo BRANCH | 3.214/3.950 = 81,3671 % (81,37 %) |
| Gate JaCoCo | All coverage checks have been met; mínimo LINE 85 % aprobado |
| Maven | BUILD SUCCESS; empaquetado y repackage aprobados |
| git diff --check | Sin errores |

Las seis omitidas conservan condiciones preexistentes: cinco de
Clase11SecurityAcceptanceTest requieren `clase11.security.enabled=true` y una base
vacía; una de M1RealAuthenticationTest requiere `M1_REAL_TESTS=YES` y acceso real M1.
No se cambió ninguna condición. La regresión M1 local sí se ejecutó, incluidos los
tests aislados de autenticación/proyección; no se atribuye a esta ejecución una
verificación contra identidad remota. Las regresiones M3/M4/M5/M7, Inbox/Outbox,
SLA, evidencias, reclasificación, seguridad y migraciones quedaron sin fallos.

Los reportes de esta ejecución están en `target/surefire-reports/` y
`target/site/jacoco/`; el resumen independiente se guardó en
`/workspace/work/m6/results.json`. No se usaron los porcentajes históricos como resultado.

Estado final de Git (índice sin cambios; sin commits, pushes ni PR):

```text
 M src/test/java/com/reclamos/backend/inbox/CoreInboxPostgresIntegrationTest.java
 M src/test/java/com/reclamos/backend/inbox/CoreIncomingEventValidatorTest.java
 M src/test/java/com/reclamos/backend/service/ExternalAttachmentServiceTest.java
 M src/test/java/com/reclamos/backend/service/TicketOutboxServiceTest.java
?? docs/integrations/m2-m6.md
?? docs/integrations/verificacion-m2-m6.md
```

HEAD final e80b042b8f17e19fccf457bb709d165de8b51e51. Cuatro archivos modificados,
dos nuevos, ninguno eliminado, cero cambios en src/main. Respaldo de los 26 archivos
previos comprobado nuevamente contra SHA-256 del manifiesto.

Quedan pendientes los acuerdos M6/Core enumerados en m2-m6.md: expectativa de prioridad,
tratamiento de null, excepción de vencimiento con duplicados, Sobres/trazas, identidad
AMQP, permisos, registro de schemas, cola/bindings M6, disponibilidad de evidencias e
intercambios bilaterales reales. Ningún test local acredita esa homologación.
