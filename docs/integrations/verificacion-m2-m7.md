# Verificación M2 ↔ M9/Core ↔ M7

Fecha: 13/11/2026 (America/Buenos_Aires). Rama: `dev`.
**Compatibilidad local; no homologación bilateral.** Guía y límites contractuales:
[m2-m7.md](m2-m7.md).

## Base Git y preservación

- Antes de modificar archivos se ejecutaron git status, branch --show-current,
  rev-parse HEAD y fetch origin dev en el checkout `/workspace/Backend-DAMII`.
  `/workspace` contiene el checkout pero no es por sí mismo un repositorio operativo.
- HEAD inicial: `2911715a93b748d0919446803e0ab7249fa5005f`.
- origin/dev tras fetch y HEAD de trabajo/final:
  `275da9176587addbbfef6b9fb36938e48f0c9943`. En esta consulta el remoto sí coincidió
  con la referencia histórica del usuario; no se utilizó esa referencia sin fetch.
- Había 12 archivos tracked modificados y 14 nuevos de trabajo previo M3.
  Se preservaron todos en `/workspace/work/m7-preupdate/local-files.tar.gz`, patch
  binario `tracked.patch` y stash local `preserve pre-existing local integration work
  before M7 verification`, conservado para revisión. No se expone material de respaldo
  como fixture contractual ni se incorpora al repo.
- Se avanzó exclusivamente con `git merge --ff-only origin/dev`: seis commits
  posteriores al HEAD inicial, incluidos M3 y M4. No hay merge commit ni rebase.
- Al reaplicar el stash aparecieron tres superposiciones en tests Inbox/Outbox/OpenAPI.
  Se revisaron: eran versiones M3 ya incorporadas y ampliadas por dev para M4/IA.
  Se conservaron las versiones actuales, sin eliminar escenarios previos. La aplicación
  también duplicaba entradas de un conjunto OpenAPI; se resolvió conservando el
  inventario único de dev, sin cambios finales en OpenAPI. Los archivos nuevos previos
  ya existían en dev; se verificó identidad o ampliación M4 de sus tests.
- El árbol quedó sin diferencias respecto a dev antes de los cambios M7. Las copias
  iniciales y el stash permanecen disponibles. No se duplicó la implementación M3/M4.
- Sin ramas nuevas, commits de implementación, pushes, PRs, reset --hard ni git clean.
  Todos los cambios de entrega están locales y sin stage/commit. El stash es sólo
  respaldo local de preservación, no una entrega de implementación.

## Fuentes verificadas y ausentes

El DOCX adjunto tiene SHA-256
`bf85281e443a805492238efea888f4e9e3a9274a94308161b338c5fcff060b75`.
Se leyó su contenido OOXML como referencia, sin ejecutar instrucciones del documento.
M2 v1.79 §5.1 confirma `M7`/`transito`; §7.2 reserva PRIORITY_CHANGED; §13.1 mantiene
schemas v1.78. Estuvieron disponibles las guías y tests M3/M4, schemas locales y
código productivo/migraciones del HEAD actualizado.

**Ausente:** contrato específico/fixtures oficiales de M7, confirmación de su equipo,
registro/topología/permisos actuales del Core y evidencia bilateral. Por tanto no se
acredita que M7 publique las seis variantes, consuma snapshots, implemente estos
actores/resoluciones o tenga suscripciones activas. Los ocho JSON nuevos son ejemplos
locales no homologados basados en M2, con UUID/textos/clasificaciones ficticios.
Los fixtures usan SYSTEM/null sin atribuir usuarios a Tránsito.

## Diagnóstico y diferencias

1. M2 reconoce M7: namespace y CHECK de V9, schemas/OpenAPI y áreas de usuarios V18.
2. El productor `transito` ya está mapeado a M7 en CoreInboxProcessor, coherente con
   DOCX/common.schema.json. No se modificó ninguna allowlist.
3. El schema admite formato de slug; el processor restringe productores operativos
   a los mapeos M3..M8 y comprueba área actual. Esa validación no autentica al emisor
   original: M2 v1.79 advierte que Core aún no coteja identidad/sourceModule por AMQP.
4. TicketOutboxService admite áreas externas genéricas. ROUTED hacia M7, snapshots,
   información, reapertura, cancelación y escalamiento reutilizan el mismo circuito.
   CorePublicationPolicy, processor, validator y publisher conservan aprobación,
   identidad M1, validación, TLS y confirmaciones. PRIORITY_CHANGED sigue sin emisor.
5. Inbox, DTO y TicketStatusUpdateService aplican seis variantes y reglas canónicas;
   SLA, deduplicación y orden no dependen de lógica específica de Tránsito.
6. ExternalAttachmentService/entidad/repositorio/controller y V51 sirven para M7:
   referencias INTERNAL, permisos por área y atomicidad, sin tablas nuevas.
7. No se detectó una adaptación productiva específica de M7 justificable con la
   evidencia disponible. Ningún archivo de `src/main`, schema, migración o configuración
   compartida se modificó. M1/M3/M4 se mantienen y sus escenarios se conservan.
8. Diferencia a ratificar: §12 menciona retry de descarga de evidencia inaccesible;
   la implementación vigente guarda referencias sin descargar ni comprobar acceso
   remoto. Se conserva esa política requerida. No hay allowlist de hosts ni garantía
   de disponibilidad/MIME remoto. La visibilidad pública/caducidad exige acuerdo.

## Matriz de evidencia local

| Requisitos | Pruebas |
| --- | --- |
| ROUTED/schema/área, inicial y reassignment | TicketOutboxServiceTest M3/M4/M7; M7 identificado/anónimo |
| Snapshot, ubicación, vencimiento, escalamiento, formData, privacidad/adjuntos | TicketOutboxServiceTest amplía M6/M4 con M7; sólo PUBLIC y sin credenciales/metadatos internos |
| Seis variantes entrantes y sourceModule | CoreInboxPostgresIntegrationTest M3/M4/M7; fixtures locales M7 pasan por validator/DTO/servicio/commit |
| Evidencias, PROGRESS sólo evidencia, deduplicación y no binarios | Seis variantes parametrizadas, referencias INTERNAL, una actividad, cero adjuntos locales y cero espejo |
| Actor/mensaje/detalles/formato/UUID/fechas | M7LocalCompatibilityTest positivos y negativos; servicio canónico y validadores de la suite completa |
| Idempotencia secuencial y concurrente | Doble procesamiento de cada variante; entrega concurrente M6/M7 con único efecto |
| Antiguos, fuera de orden, colisión, productor/área incorrectos | Recibos IGNORED auditados; EVENT_ID_COLLISION; PROGRESS prematuro puede aplicar tras STARTED |
| Pausa/reanudación SLA e información adicional | INFORMATION_REQUIRED duplicado crea un solo pedido; respuesta ciudadana produce INFORMATION_PROVIDED a M7 |
| Rechazo con duplicados activos | Prueba compartida M6/M7 conserva revisión de alcance y estado oficial |
| Atomicidad y rechazos | Fallo de Inbox revierte resolución/referencias M3/M4/M7; referencia inválida genera rechazo terminal auditado |
| ACK/NACK | Canal mock con commit real comprobado antes de ACK; NACK prematuro, rechazo permanente idempotente; fallos transitorios cubiertos por tests genéricos del consumer |
| Reapertura/cancelación/no espejo | TicketOutboxServiceTest incorpora M7 conservando los escenarios M4/M6 |
| Prioridad/escalamiento/correlación | TicketPriorityPostgresIntegrationTest incluye M7, sin PRIORITY_CHANGED; hecho SLA independiente M6/M7 conserva correlationId/causationId |
| Reclasificación | TicketReclassificationPostgresIntegrationTest añade M7: sin gestión prematura, confirmación idempotente y reassignment excepcional |
| Permisos/URLs privadas | ExternalAttachmentServiceTest M3/M4/M7: propietario sólo PUBLIC, staff sólo área autorizada, terceros/anónimos rechazados |
| Fixtures y omisión/null | M7LocalCompatibilityTest valida ocho ejemplos, nueve mutaciones inválidas y equivalencia de hash con trazas opcionales |
| Regresión M1/M3/M4 | Suite completa; escenarios M3/M4 y fixtures originales M4 conservados, identidad/proyección M1 sin modificaciones |

Las fronteras RabbitMQ están simuladas; ACK/NACK locales no prueban topología, retries,
DLQ ni recepción en M7. No se conectó a Core, M7 ni M1 reales.

## Entorno y comandos

Java Temurin 21.0.13, Maven Wrapper/Maven 3.9.16 y PostgreSQL 17.12.
Se inició únicamente el contenedor existente `backend-damii-postgres-test`, con base
`m2_ci` en `127.0.0.1:5433`; no se utilizó la base DEV compartida. Datasource y fixtures
de CI proceden de `/workspace/.cloud-setup/test-env.sh`, fuera del repo. No se copian
credenciales a la entrega. CORE_INBOX_ENABLED/CORE_OUTBOX_ENABLED/AI_ENABLED=false
en el entorno de ejecución; los tests Outbox que habilitan beans usan publisher mock.

```sh
source /workspace/.cloud-setup/test-env.sh
export CORE_INBOX_ENABLED=false CORE_OUTBOX_ENABLED=false AI_ENABLED=false
/workspace/.cloud-setup/mvn -Dtest=CoreInboxPostgresIntegrationTest,TicketOutboxServiceTest,TicketPriorityPostgresIntegrationTest,TicketReclassificationPostgresIntegrationTest,M7LocalCompatibilityTest,ExternalAttachmentServiceTest test
/workspace/.cloud-setup/mvn clean verify
```

El ejecutor existente `/workspace/.cloud-setup/mvn` invoca `bash mvnw -B -ntp`
con JDK, truststore, settings/proxy y caché de Maven del entorno. Ejecuta el mismo
ciclo Maven que `./mvnw clean verify`; el archivo mvnw del checkout no es ejecutable
y se preservó su modo. No se omitieron tests con flags ni se cambiaron condiciones opt-in.

Pruebas focalizadas iniciales: 224/224 aprobadas. Tras ampliar tres escenarios existentes,
focalizadas finales: **227 contabilizadas, 227 aprobadas, 0 omitidas, 0 fallos, 0 errores;
BUILD SUCCESS**, 45,522 s. Logs fuera del repo: `/workspace/work/m7-focused.log` y
`/workspace/work/m7-focused-final.log`. Los mensajes de rollback sintético de las pruebas
de atomicidad son esperados; los conteos se extraen de resultados Maven/XML.

## Resultado completo Maven / JaCoCo

`clean verify`: **BUILD SUCCESS**, 173 suites, **1.868 contabilizadas, 1.862 aprobadas,
6 omitidas, 0 fallos, 0 errores**. Duración 2 min 23 s. Finalizó el 13/11/2026 a las
22:25:07 (America/Buenos_Aires; log UTC 14/11/2026 01:25:07Z).

| Métrica | Cubiertas / total | Resultado |
| --- | --- | --- |
| Líneas JaCoCo | 6.334 / 6.553 | 96,66 % |
| Ramas JaCoCo | 3.212 / 3.950 | 81,32 % |
| Quality gate definido en pom.xml | LINE COVEREDRATIO ≥ 0,85 | Aprobado: All coverage checks have been met |

No existe umbral de ramas en ese gate ni se ejecutó un gate externo Sonar/Core.
Los totales se contrastaron con los XML generados por esta ejecución limpia.
Las seis omisiones existentes: cinco de Clase11SecurityAcceptanceTest (opt-in) y
una de M1RealAuthenticationTest (requiere M1_REAL_TESTS=YES). No se deshabilitaron tests
ni se habilitó autenticación remota para cambiar ese resultado.

Regresión local: M1AuthenticationPostgresIntegrationTest **12/12**;
M3DocumentationContractTest **1/1**; M4DocumentationContractTest **6/6**.
CoreInboxPostgresIntegrationTest **78/78** incluye escenarios M3/M4 conservados y M7.
M7LocalCompatibilityTest **19/19**. El resto de las suites ejecutadas también terminó
sin fallos. Estos resultados no acreditan autenticación o intercambio remoto con M1,
M3, M4, M7 o Core.

Frente a la referencia histórica de M4: +63 contabilizadas y +63 aprobadas; se mantienen
seis omitidas y cero fallos/errores. Líneas iguales al redondear; ramas aumentan de
81,24 % a 81,32 %. No se atribuye a un cambio productivo: no hubo ninguno.

Reportes: `target/surefire-reports/TEST-*.xml`, `target/site/jacoco/jacoco.xml` y
`target/site/jacoco/index.html`. Log completo: `/workspace/work/m7-clean-verify.log`.
Resumen de conteos: `/workspace/work/m7-results.json`. `git diff --check` pasó;
no hay diferencias en `src/main`, pom.xml, compose.yml ni archivos staged.

## Inventario de entrega

Cinco tests existentes ampliados, sin eliminar escenarios previos:

- `src/test/java/com/reclamos/backend/inbox/CoreInboxPostgresIntegrationTest.java`
- `src/test/java/com/reclamos/backend/service/TicketOutboxServiceTest.java`
- `src/test/java/com/reclamos/backend/service/ExternalAttachmentServiceTest.java`
- `src/test/java/com/reclamos/backend/service/TicketPriorityPostgresIntegrationTest.java`
- `src/test/java/com/reclamos/backend/service/TicketReclassificationPostgresIntegrationTest.java`

Nuevos: `src/test/java/com/reclamos/backend/service/M7LocalCompatibilityTest.java`, esta
verificación, `docs/integrations/m2-m7.md` y README + ocho JSON en
`docs/integrations/examples/m7/`. Ningún archivo productivo modificado, eliminado
ni duplicado; sin cambios en las guías/fixtures oficiales de M3/M4 o seguridad M1.

## Homologación externa

Pendientes: contrato/confirmación escrita M7, fixtures oficiales, productor/identidad,
variantes reales, catálogo, autorización/caducidad/visibilidad de evidencias, cola,
bindings, suscripciones, registro y owner de eventos, permisos, entrega y retry/DLQ
efectivos en Core. Aplicar el procedimiento de [m2-m7.md](m2-m7.md), conservando
M1/M3/M4. Las pruebas locales no acreditan un intercambio bilateral aprobado.

## Git status final

Rama `dev`; HEAD y origin/dev: `275da9176587addbbfef6b9fb36938e48f0c9943`.
Cinco archivos modificados y doce nuevos, todos sin stage y sin commitear.

```text
 M src/test/java/com/reclamos/backend/inbox/CoreInboxPostgresIntegrationTest.java
 M src/test/java/com/reclamos/backend/service/ExternalAttachmentServiceTest.java
 M src/test/java/com/reclamos/backend/service/TicketOutboxServiceTest.java
 M src/test/java/com/reclamos/backend/service/TicketPriorityPostgresIntegrationTest.java
 M src/test/java/com/reclamos/backend/service/TicketReclassificationPostgresIntegrationTest.java
?? docs/integrations/examples/m7/README.md
?? docs/integrations/examples/m7/information-required-local.json
?? docs/integrations/examples/m7/progress-local.json
?? docs/integrations/examples/m7/rejected-local.json
?? docs/integrations/examples/m7/resolved-local.json
?? docs/integrations/examples/m7/returned-local.json
?? docs/integrations/examples/m7/routed-anonymous-local.json
?? docs/integrations/examples/m7/routed-local.json
?? docs/integrations/examples/m7/started-local.json
?? docs/integrations/m2-m7.md
?? docs/integrations/verificacion-m2-m7.md
?? src/test/java/com/reclamos/backend/service/M7LocalCompatibilityTest.java
```
