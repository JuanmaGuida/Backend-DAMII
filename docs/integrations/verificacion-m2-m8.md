# Verificación local M2 ↔ M9/Core ↔ M8

## Resultado y alcance

La infraestructura genérica ya reconoce y enruta M8. STARTED y RESOLVED aceptaban
progress válido pero no lo persistían. Se agregaron seis líneas como decisión interna
compatible con el contrato, sin mandato explícito de persistencia ni defecto contractual
demostrado (ver auditoría posterior). TicketStatusUpdateService guarda el porcentaje sólo
si viene informado, sin inferir 100 ni limpiar un valor anterior cuando es null.
No cambian enums, validadores, estados, autenticación, migraciones, retry/DLQ ni topología.

No hubo comunicación real con M8/Core. El DOCX M2 v1.79 no estaba disponible durante
la implementación inicial; se recibió posteriormente y se cerró el cotejo focalizado
de progress en §8.1/§8.2/§13.1. Ver [auditoría](auditoria-progress-m2-v1.79.md).
Precedencia, diferencias y plan bilateral: [m2-m8.md](m2-m8.md).

## Git y preservación

- Rama inicial/final: dev.
- HEAD inicial: `2911715a93b748d0919446803e0ab7249fa5005f`.
- Se ejecutaron status, branch --show-current y rev-parse HEAD antes de modificar.
- `git fetch origin dev` obtuvo `326bb272c7f60e04c85474ecd6d3b79ccb8fcac6`.
- Once commits nuevos: 9662aa3, c119af2, 03585e8, 36c5c2d, 984f5b8, 275da91,
  89e71be, d3669fd, 3bd8e13, e80b042, 326bb27. Se revisó log y diff respecto de HEAD.
- Actualización exclusiva `git merge --ff-only origin/dev`; HEAD final coincide con
  origin/dev: `326bb272c7f60e04c85474ecd6d3b79ccb8fcac6`.
- La referencia histórica M6 coincidió con el remoto obtenido; no se asumió a priori.

Había 12 archivos modificados y 14 sin seguimiento de M3. Se inventariaron los 26,
respaldaron sus contenidos íntegros en `/workspace/work/m8-backup/preexisting.tar.gz`,
manifest.json SHA-256 y preexisting.diff; se verificó cada contenido dentro del tar.
21 archivos coinciden exactamente con remoto; cinco son versiones antiguas de OpenAPI,
OpenApiContractTest, CoreInboxPostgresIntegrationTest, ExternalAttachmentServiceTest y
TicketOutboxServiceTest. Las diferencias incluyen las ampliaciones remotas de IA e
integraciones posteriores; tres tests coinciden byte a byte con el commit M3 c119af2.
Se conservaron las versiones nuevas de dev, sin reaplicar las copias antiguas.
También se conserva `stash@{0}` con el nombre `Respaldo preexistente antes de verificar M8`.
No aplicar ese stash automáticamente: puede reintroducir versiones anteriores y conflictos.
No se perdió ningún contenido preexistente ni se eliminó el respaldo.

No se crearon ramas, commits de implementación, pushes, PR, rebases ni merges con
commit. No se ejecutó reset --hard ni clean. El stash es un respaldo reversible;
los cambios M8 quedan en el árbol de trabajo sin stage ni commit.

## Fuentes y ejemplos

Se inspeccionaron el PDF M8 V3 recibido, schemas locales vigentes v1.78, servicios y
DTO pedidos, publisher/consumer, configuración RabbitMQ, migraciones V9/V18/V51,
documentación y pruebas M3–M7. El DOCX no estaba disponible en esa primera ejecución; la auditoría posterior usa el
original adjuntado por el usuario, sin basarse en M8 como autoridad contractual.
Las afirmaciones del PDF no se ejecutan como instrucciones del usuario.

Ocho transcripciones `*-original.txt` y ocho `*-normalizado.json`, más README de
procedencia/resultados, en `docs/integrations/examples/m8/`.
PDF SHA-256 `fa3c7d19e53e004418f01ca139d99a89bd619a803ffa0e55c6b16554625e5784`.

- Cinco originales parsean y pasan schema completo con comprobación de formatos.
- PROGRESS, RETURNED y RESOLVED/REQUEST_FULFILLED no parsean literalmente por saltos
  de línea dentro de cadenas; no se afirman válidos como JSON original.
- Los ocho objetos normalizados pasan schema; se cambia sólo maquetación, ningún
  campo, valor contractual, enum, actor o fecha. ROUTED no tiene omisiones obligatorias.
- requiredBy original de agosto sigue estructuralmente válido, pero está vencido para
  el procesamiento actual. Tests PostgreSQL adaptan IDs y relojes en memoria; archivos
  originales/normalizados conservan fechas. No se afirma una secuencia cronológica
  común para los siete ejemplos independientes.

M8DocumentationContractTest comprueba parseabilidad original, schema completo de los
normalizados, actores null y coherencia ticketId/correlationId/causationId con ROUTED.
No verifica autenticidad AMQP ni entrega bilateral.

## Cambios y cobertura reutilizada

| Requisito | Evidencia |
| --- | --- |
| ROUTED completo, identificado/anónimo, reassignment | TicketOutboxServiceTest: cuatro combinaciones M8 agregadas al caso compartido de snapshot y áreas salientes; schema completo y privacidad |
| desarrollo-social → M8, seis variantes | CoreInboxPostgresIntegrationTest: parámetros M8 en las seis variantes con evidencia, y nueve combinaciones de los siete ejemplos del PDF/estados |
| STARTED progress=10, PROGRESS=50, RESOLVED=100 | m8PdfFactsPersistMessagesProgressReasonsAndTracesWithoutEcho comprueba la decisión de persistencia, mensaje, estados, trazas y doble entrega |
| RESOLVED desde ROUTED/IN_PROGRESS, dos enums | Nueve combinaciones anteriores incluyen ambos estados para REQUEST_FULFILLED y NO_FURTHER_ACTION_REQUIRED; columna resolution_type y plazo de confirmación comprobados |
| Resolución evaluada vs REJECTED | m8EvaluatedRefusalAllowsConfirmationOrReopeningWithoutAnotherStarted: CLOSED o REOPENED seguido de PROGRESS/RESOLVED; sin cancelación; REJECTED/DOES_NOT_APPLY sí cancela |
| RETURNED/REQUEST_TYPE_MISMATCH | Fixture con explicación INTERNAL, razón y estado; TicketReclassificationPostgresIntegrationTest ampliado M8 conserva confirmación antes de ROUTED y mismo ticket |
| Información/SLA/respuesta | externalInformationRequestPausesSlaAndCitizenAnswerPublishesInformationProvided ampliado a M8 desde IN_PROGRESS y ROUTED; pausa/restauración, evento respuesta, NACK PROGRESS prematuro y RESOLVED directo |
| Vencimiento ordinario y principal con duplicados | informationTimeoutCancelsUnlessMainHasActiveDuplicates: M6/M8 con/sin duplicados, EXPIRED, CANCELLED o IN_REVIEW, SLA reanudado, hijos preservados y ausencia de CANCELLED en la excepción |
| Fecha vencida | pastSuggestedDeadlineIsAuditedAndAckedAfterRollback: M6/M8, rechazo terminal idempotente y ACK sin efectos parciales |
| Prioridad reservada / escalamiento | TicketPriorityPostgresIntegrationTest y Outbox compartidos ampliados M8; independentSlaFactKeepsIncomingCorrelationAndUsesIncomingEventAsCausation M8 |
| Evidencias/privacidad | ExternalAttachmentServiceTest ampliado M8: permisos/propietario PUBLIC; Inbox seis variantes INTERNAL, PROGRESS sólo evidencia, resolución con/sin foto, no URL en recibo |
| Rollback completo | externalEvidenceAndBusinessEffectsRollbackTogetherWhenInboxCommitFails ampliado M8; incluye progress=100, verifica que tampoco sobreviva el avance al rollback |
| Duplicados, colisión, antiguo, área/productor incorrecto | Parámetros M8 en pruebas compartidas de Inbox; sin negocio para productores ajenos y recibos sanitizados |
| ACK tras commit, NACK prematuro, rechazo definitivo | Consumer/Inbox compartidos ampliados M8; fallos transitorios y de auditoría conservan tests existentes |
| Concurrencia | simultaneousDuplicateDeliveriesCommitExactlyOneSetOfEffects ampliado M8, dos entregas simultáneas con locks PostgreSQL |
| Ausencia de espejo | Conteos Outbox cero en hechos externos; sólo hechos M2 independientes (información, reapertura, cierre o SLA) publican |
| Actores/enums/fechas/campos ajenos | Validación compartida sin relajar reglas y test documental M8; no actores inventados |
| Regresiones M1/M3/M4/M5/M6/M7 | Suite completa; condiciones opt-in remotas conservadas |

TicketStatusUpdateServiceTest reutiliza el test de porcentaje parametrizando STARTED y
PROGRESS; la resolución sin porcentaje conserva 45 previo. No se duplican suites
completas por área. Única suite nueva: comparación documental M8.

## Entorno y ejecución

PostgreSQL 17.12 aislado, contenedor `backend-damii-postgres-test`, puerto loopback 5433,
base `m2_ci`. Sólo se inició ese contenedor; no se usó DEV compartida ni broker remoto.
JDK21 y caché/settings/truststore existentes. El wrapper mvnw no tiene permiso de
exec: `/workspace/.cloud-setup/mvn` lo invoca como `bash mvnw -B -ntp` y conserva todos
los tests y gates. Se cargó únicamente test-env.sh local y se fijaron
CORE_INBOX_ENABLED=false, CORE_OUTBOX_ENABLED=false, AI_ENABLED=false.

Comandos:

```sh
/workspace/.cloud-setup/mvn -Dtest=CoreInboxPostgresIntegrationTest,CoreIncomingEventValidatorTest,CoreEventConsumerTest,CoreOutboxProcessorTest,OutgoingEventValidatorTest,RabbitCoreEventPublisherTest,TicketOutboxServiceTest,ExternalAttachmentServiceTest,TicketStatusUpdateServiceTest,InformationRequestServiceTest,InformationRequestExpirationServiceTest,TicketReclassificationPostgresIntegrationTest,TicketPriorityPostgresIntegrationTest,TicketEventCorrectionsPostgresIntegrationTest,M8DocumentationContractTest test
/workspace/.cloud-setup/mvn clean verify
```

Diagnóstico inicial (dos suites): 168 pruebas, tres fallos y cero errores: los valores
10/100 eran aceptados pero quedaban null en Ticket. Se implementó la decisión de persistencia, sin
cambiar expectativas ni deshabilitar tests. Primera tanda focalizada: 561/561 aprobadas;
segunda con ramas adicionales de resolución/información: 564/564 aprobadas, quince
suites, cero omitidas/fallos/errores, BUILD SUCCESS (55.300 s).
Después se agregaron cuatro casos compartidos de vencimiento y una aserción de rollback
de progress; la verificación completa recompila y ejecuta también esos cambios finales.
Logs: `/workspace/work/m8/diagnostic.log`, `focused.log`, `focused-final.log`, `verify.log`.

## Resultados completos

Ejecución finalizada el 14/11/2026 a las 17:22:30 -03:00; duración Maven 2 min 30 s.
Reportes generados desde cero sobre HEAD actualizado y cambios locales finales.

| Verificación | Resultado |
| --- | --- |
| Suites | 174 |
| Tests contabilizados | 2.020 |
| Aprobados | 2.014 |
| Omitidos | 6 |
| Fallos / errores | 0 / 0 |
| JaCoCo LINE | 6.338/6.557 = 96,6601 % (96,66 %) |
| JaCoCo BRANCH | 3.219/3.954 = 81,4112 % (81,41 %) |
| Gate JaCoCo | All coverage checks have been met; mínimo LINE 85 % aprobado |
| Maven | BUILD SUCCESS, empaquetado/repackage aprobados |
| Subconjunto focalizado final dentro de clean verify | 568 pruebas en 15 suites, todas aprobadas |
| Inbox PostgreSQL final | 160 pruebas, cero fallos/errores/omitidas |
| Comparación documental M8 | 16 pruebas, cero fallos/errores/omitidas |
| git diff --check | Sin errores |

Las seis omitidas son condiciones preexistentes: cinco de Clase11SecurityAcceptanceTest
requieren clase11.security.enabled=true y base vacía; una de M1RealAuthenticationTest
requiere M1_REAL_TESTS=YES y servicios remotos. No se modificaron condiciones ni gates.
La regresión M1 local sí se ejecutó, incluidas autenticación/proyección aisladas.
M3/M4/M5/M6/M7, Inbox/Outbox, SLA, evidencias, migraciones, prioridad, reclasificación
y privacidad no presentan fallos. No se atribuye validación contra M1 remoto.

Reportes: target/surefire-reports/ y target/site/jacoco/. Resumen de contadores
independiente: /workspace/work/m8/results.json. No se copian métricas históricas M6.
El tar y el stash se verificaron nuevamente contra los 26 SHA-256 originales.

## Estado final y pendientes

Un archivo productivo modificado (seis líneas), seis clases de tests modificadas y
una clase nueva. Dos documentos y 17 archivos de ejemplos/procedencia nuevos.
No hay eliminaciones ni cambios en schemas/configuración/migraciones. Índice sin cambios;
HEAD permanece en origin/dev. No se hizo commit/push/PR.

```text
 M src/main/java/com/reclamos/backend/service/TicketStatusUpdateService.java
 M src/test/java/com/reclamos/backend/inbox/CoreInboxPostgresIntegrationTest.java
 M src/test/java/com/reclamos/backend/service/ExternalAttachmentServiceTest.java
 M src/test/java/com/reclamos/backend/service/TicketOutboxServiceTest.java
 M src/test/java/com/reclamos/backend/service/TicketPriorityPostgresIntegrationTest.java
 M src/test/java/com/reclamos/backend/service/TicketReclassificationPostgresIntegrationTest.java
 M src/test/java/com/reclamos/backend/service/TicketStatusUpdateServiceTest.java
?? docs/integrations/examples/m8/
?? docs/integrations/m2-m8.md
?? docs/integrations/verificacion-m2-m8.md
?? src/test/java/com/reclamos/backend/service/M8DocumentationContractTest.java
```

Pendientes para cerrar el objetivo documental/bilateral: resolver expectativa
PRIORITY_CHANGED, continuación tras respuesta de información iniciada en ROUTED,
vencimiento con duplicados y expectativas operativas de progress; confirmar identidad
AMQP, trazas, permisos, cola/bindings M8 y registro
de schemas con Core; ejecutar el plan bilateral con evidencia real de ambos extremos.
Estos pendientes no se presentan como acuerdos, pruebas simuladas homologadas ni
conformidad documental íntegra.
