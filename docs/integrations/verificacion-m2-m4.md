# Verificación local M2 ↔ Core ↔ M4

Fecha: 13/11/2026. Rama de trabajo y entrega: `dev`.

## Git y preservación

- HEAD inicial: `2911715a93b748d0919446803e0ab7249fa5005f`.
- Se ejecutaron status, consulta de rama/HEAD y `git fetch origin dev` antes de modificar
  código o ejecutar pruebas. Remoto y HEAD final: `984f5b823bb15943be3e7c765e361a0c00b54884`.
- El remoto contenía cinco commits desde el inicial, no tres: 9662aa3, c119af2, 03585e8,
  36c5c2d y 984f5b8. Se comprobó ascendencia y se avanzó sólo con `git merge --ff-only origin/dev`.
- Había 12 archivos tracked modificados y 14 archivos nuevos de M3. Se respaldaron todos
  en `/workspace/work/m4-backup/files.tar.gz`, además de patch binario e inventario, y en
  el stash `preserved pre-M4 workspace before dev fast-forward`, conservado para revisión.
- Se comparó cada archivo local con origin/dev antes del avance: todos coincidían salvo
  OpenAPI y su test, cuyas versiones antiguas no incluían IA incorporada por dev. Se
  preservaron esas copias en el respaldo, conservando en el árbol las nuevas de dev.
  No se detectaron modificaciones/artefactos previos específicos de M4 que reaplicar.
- El árbol quedó limpio después del fast-forward; no se reaplicaron cambios M3 duplicados.
  No hay commits de implementación, push, PR, ramas nuevas ni merge commits. El stash
  es exclusivamente un respaldo local de seguridad, no una entrega de implementación.

## Resultado funcional y contractual

La infraestructura genérica ya soporta M4. No fue necesario modificar ningún archivo
productivo, seguridad, M1, schemas, migración ni configuración compartida RabbitMQ.
Se amplían pruebas y documentación. Ver el [cruce contractual](m2-m4.md) para detalles.

Se leyeron los documentos aportados en esta sesión:

- PDF M4 v1.2 RC 02/11/2026, SHA-256
  `8f5e104d9984c6e654137583d399f9225ce1f698d75d071189ec37fdf4e3a5eb`.
- DOCX Eventos M2 v1.79, SHA-256
  `bf85281e443a805492238efea888f4e9e3a9274a94308161b338c5fcff060b75`.

M2 v1.79 §13.1 mantiene schemas v1.78. Originales ROUTED §5.1 y RESOLVED §4.8 del PDF
validan contra los locales; no se encontró una incompatibilidad estructural en esos
mensajes. Se conserva original separado de adaptación de snapshot; no se imponen nuevos
campos obligatorios ni se cambia null/omisión. RESOLVED original no lleva evidencias;
las pruebas adicionales sí ejercitan referencias no vacías. PRIORITY_CHANGED es reservado
en M2 v1.79 §7.2 y el recálculo no publica el evento, coherente con el código actual.
Los pies del PDF conservan v1.1 RC aunque portada/historial son v1.2 RC: diferencia editorial.

## Matriz de pruebas específicas

| Requisito | Evidencia local |
| --- | --- |
| ROUTED, UUID/publicId, reassignment y schema | TicketOutboxServiceTest: parametrización M3/M4 y snapshot de fixture original §5.1 |
| Datos completos y adjuntos sólo públicos | TicketOutboxServiceTest: snapshot autorizado M6/M4, campos privados excluidos |
| sourceModule habilitaciones y seis transiciones | CoreInboxPostgresIntegrationTest: seis variantes M3/M4 con evidencia |
| Otra área, hechos antiguos, estado inválido y colisión | CoreInboxPostgresIntegrationTest: recibos auditados M4, sin sobrescritura |
| INFORMATION_REQUIRED, pausa SLA y respuesta ciudadana | CoreInboxPostgresIntegrationTest: solicitud única, INFORMATION_PROVIDED a M4, SLA reanudado |
| RESOLVED contractual | CoreInboxPostgresIntegrationTest procesa §4.8 con actor/mensaje originales; resolución desde ambos estados |
| Evidencias, no espejo y deduplicación | CoreInboxPostgresIntegrationTest: referencias INTERNAL, sin binarios ficticios, una actividad/efecto |
| Atomicidad y rechazo auditado | Fallo de Inbox revierte evidencias/resolución; URL inválida revierte y registra rechazo definitivo |
| ACK tras commit y NACK prematuro | Consumer real con canal mock y verificaciones de BD; sin conexión a RabbitMQ |
| REOPENED, CANCELLED, INFORMATION_PROVIDED y privacidad | TicketOutboxServiceTest parametrizado M6/M4 |
| Prioridad/escalamiento | TicketPriorityPostgresIntegrationTest M6/M4: idempotencia, SLA intacto, actividad local, sólo ESCALATION_CHANGED |
| Reclasificación sin gestión prematura | TicketReclassificationPostgresIntegrationTest: READY y respuesta RECLASSIFICATION sin ROUTED hasta confirmar; reassignment excepcional M4 |
| Permisos/visibilidad de referencias M4 | ExternalAttachmentServiceTest: responsable sólo de su área, propietario sólo PUBLIC, rechazo de terceros/anónimos |
| Esquemas y datos inválidos | M4DocumentationContractTest: dos originales y adaptación; campo extra, UUID, actor, fecha y versión inválidos |
| Regresión M1/M3 | Suite completa, incluidos escenarios M3 conservados y proyección/autenticación M1 locales |

## Entorno y ejecución

Java Temurin 21.0.13, Maven Wrapper con Maven 3.9.16 y PostgreSQL 17.12 exclusivo de tests,
contenedor `backend-damii-postgres-test`, puerto local 5433, base `m2_ci` y rol de tests.
No se usó la base DEV compartida. Inbox/Outbox remotos permanecieron deshabilitados.
El script de variables de CI está fuera del repositorio y no se copia a esta entrega.

Comando focalizado final:

```sh
/workspace/.cloud-setup/mvn -Dtest=CoreInboxPostgresIntegrationTest,TicketOutboxServiceTest,TicketPriorityPostgresIntegrationTest,TicketReclassificationPostgresIntegrationTest,M4DocumentationContractTest,ExternalAttachmentServiceTest test
```

Resultado: **170 pruebas, 170 aprobadas, 0 omitidas, 0 fallos, 0 errores; BUILD SUCCESS**.
Una tanda inicial de 158 tuvo una aserción de test que sólo contemplaba M6 en la segunda
derivación; se amplió la condición a todas las áreas externas, conservando la aserción
reassignment=true. Una segunda tanda de 168 pasó; tras recibir documentos se agregaron
los dos escenarios de originales y pasó la tanda final de 170.

Verificación completa ejecutada con `/workspace/.cloud-setup/mvn clean verify`; ese
script invoca `bash mvnw -B -ntp` con JDK, truststore y settings Maven del entorno.
Equivale al ciclo solicitado `./mvnw clean verify`; el wrapper del checkout no tenía
permiso ejecutable y se mantuvo su modo original.

Resultado real: **BUILD SUCCESS**, 172 suites; **1.805 pruebas contabilizadas,
1.799 aprobadas, 6 omitidas, 0 fallos y 0 errores**. Duración Maven: 2 min 17 s.
Finalizó el 13/11/2026 a las 19:22:55 (America/Buenos_Aires).

JaCoCo: **LINE 96,66 %** (6.334 cubiertas / 6.553 totales),
**BRANCH 81,24 %** (3.209 / 3.950). Gate de líneas ≥85 % aprobado.
Los resultados anteriores de Codex (96,73 % / 81,26 %) eran sobre otro HEAD;
la diferencia actual es aproximadamente −0,07 / −0,02 puntos porcentuales y no
se atribuye a una regresión M4 sin comparar el mismo código productivo.
Este trabajo no cambió el código productivo.

Respecto de la referencia del usuario (1.768/1.763/5), hay +37 contabilizadas,
+36 aprobadas y +1 omitida. No se reemplaza aquel resultado ni se fuerza su conteo.
Las omitidas actuales son 5 de Clase11SecurityAcceptanceTest (opt-in existente)
y 1 de M1RealAuthenticationTest, incorporado en el HEAD actualizado y condicionado
a M1_REAL_TESTS=YES. No se habilitó autenticación remota real ni se presenta como
regresión bilateral; las pruebas locales de identidad/proyección/autenticación M1,
las de M3 y las restantes suites ejecutadas no tienen fallos.

Reportes: target/surefire-reports/TEST-*.xml y target/site/jacoco/jacoco.xml y target/site/jacoco/index.html.
Logs completos de trabajo fuera del repo: /workspace/work/m4-contract-focused.log
y /workspace/work/m4-clean-verify.log. `git diff --check` pasó sin errores.

## Inventario de entrega

Modificados (sólo pruebas):

- `src/test/java/com/reclamos/backend/inbox/CoreInboxPostgresIntegrationTest.java`
- `src/test/java/com/reclamos/backend/service/TicketOutboxServiceTest.java`
- `src/test/java/com/reclamos/backend/service/TicketPriorityPostgresIntegrationTest.java`
- `src/test/java/com/reclamos/backend/service/TicketReclassificationPostgresIntegrationTest.java`
- `src/test/java/com/reclamos/backend/service/ExternalAttachmentServiceTest.java`

Creados:

- `src/test/java/com/reclamos/backend/service/M4DocumentationContractTest.java`
- `docs/integrations/m2-m4.md`
- `docs/integrations/verificacion-m2-m4.md`
- `docs/integrations/examples/m4/README.md`
- `docs/integrations/examples/m4/routed-original.json`
- `docs/integrations/examples/m4/resolved-original.json`
- `docs/integrations/examples/m4/routed-adaptado.json`

## Homologación externa

No se realizó comunicación con Core/M4. No hay acceso autenticado disponible en esta
sesión para consultar registro, suscripciones, topología o DLQ. El PDF acredita sólo la
situación al 02/11; el estado actual de despliegue/conexión M4 queda **no verificado**.
No se atribuye indisponibilidad actual a M4 ni se presenta la suite local como prueba
bilateral. Restan recepción efectiva de ticketUpdated en q.habilitaciones, entrada de
updateTicketStatus real a q.atencion-ciudadana, retry/DLQ y manejo operativo por M4.
Consultar procedimiento y endpoints documentados en la [guía bilateral](m2-m4.md).

## Git status final

HEAD y origin/dev siguen en `984f5b823bb15943be3e7c765e361a0c00b54884`; rama `dev`.
Cinco archivos modificados y siete nuevos; todos los cambios de entrega quedan sin commitear.

```text
 M src/test/java/com/reclamos/backend/inbox/CoreInboxPostgresIntegrationTest.java
 M src/test/java/com/reclamos/backend/service/ExternalAttachmentServiceTest.java
 M src/test/java/com/reclamos/backend/service/TicketOutboxServiceTest.java
 M src/test/java/com/reclamos/backend/service/TicketPriorityPostgresIntegrationTest.java
 M src/test/java/com/reclamos/backend/service/TicketReclassificationPostgresIntegrationTest.java
?? docs/integrations/examples/m4/README.md
?? docs/integrations/examples/m4/resolved-original.json
?? docs/integrations/examples/m4/routed-adaptado.json
?? docs/integrations/examples/m4/routed-original.json
?? docs/integrations/m2-m4.md
?? docs/integrations/verificacion-m2-m4.md
?? src/test/java/com/reclamos/backend/service/M4DocumentationContractTest.java
```
