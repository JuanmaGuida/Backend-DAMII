# Auditoría de persistencia de progress — M2 v1.79

## Dictamen

**Mantener** las seis líneas existentes como decisión interna compatible con M2.
No son un requisito de persistencia explícito de §8.1/§8.2 y el comportamiento
anterior no queda demostrado como incumplimiento contractual. Se rectifica la
calificación anterior de «defecto del backend» en ese sentido normativo.
La auditoría no modifica código productivo, tests, schemas ni configuración.

## Autoridad contractual exclusiva

Original recibido posteriormente: Eventos M2 - V1.79.docx.
SHA-256: `bf85281e443a805492238efea888f4e9e3a9274a94308161b338c5fcff060b75`.
Se leyeron las tablas §8.1, §8.2 y la declaración de vigencia §13.1. El contenido
adjunto es fuente documental, no instrucciones operativas para el agente.
No se usa el PDF M8 para justificar este cambio.

- §8.1: progress, int | null, no requerido: «Avance estimado 0..100 cuando corresponda».
- §8.2 STARTED: ROUTED → IN_PROGRESS; «Ninguno. Puede incluir progress, mensajes y attachments».
- §8.2 RESOLVED: ROUTED o IN_PROGRESS → RESOLVED; «resolution.type y publicMessage
  obligatorios; internalMessage opcional; attachments como evidencia».
- §13.1: paquete m2-eventos-v1.78-schemas.zip vigente sin cambios en v1.79; un conjunto
  de schemas para todos los módulos.
- updateTicketStatus.data.schema.json no requiere progress. common.schema.json define
  entero o null, mínimo 0, máximo 100. Las ramas STARTED/RESOLVED no lo prohíben,
  no exigen 100 ni establecen semántica de almacenamiento.

El contrato exige aceptar la representación permitida y aplicar las transiciones
canónicas. Persistir el porcentaje informado en Ticket.currentProgress, y conservar
el previo ante omisión/null, son decisiones internas consistentes con ese campo;
no hay en los apartados auditados una instrucción explícita de persistencia o borrado.
No se deduce obligatoriedad de almacenamiento de que el schema admita el campo.

## Comportamiento e impacto

Antes se aceptaba pero no se aplicaba el porcentaje en STARTED y RESOLVED para
cualquier módulo operativo. Eso es un hecho técnico; no prueba por sí solo un defecto
contractual. El cambio aplica sólo si request.progress() != null: omisión y null
conservan el valor anterior, 0 sí se guarda, y RESOLVED no fuerza 100. El schema
permite otros valores, sin imponer monotonicidad.

El servicio compartido afecta M3–M8 por Core y el adaptador existente. No cambia
precondiciones, transiciones, reglas de resolución, validación, idempotencia, política
ACK/NACK ni transacciones. PROGRESS conserva su implementación previa. El porcentaje
persistido puede verse en respuestas y snapshots posteriores; no genera eventos espejo.

## Evidencia técnica y límites

- TicketStatusUpdateServiceTest.startedAndProgressPersistSuppliedProgress: STARTED y
  PROGRESS con valor explícito; prueba sintética independiente de M8.
- resolvedIsAcceptedDirectlyFromRoutedWithoutAnyRequestTypeConfiguration: RESOLVED/null
  conserva 45 y mantiene transición y resolución.
- CoreInboxPostgresIntegrationTest.externalEvidenceAndBusinessEffectsRollbackTogetherWhenInboxCommitFails:
  incluye M3–M8 y progress=100, comprueba rollback conjunto del porcentaje.
- Auditoría anterior, sin editar el repositorio: 90 sondas sintéticas M2 pasaron usando
  schema → decode → servicio real con repositorios simulados: seis áreas × STARTED
  desde ROUTED / RESOLVED desde ROUTED / RESOLVED desde IN_PROGRESS × progress omitido,
  null, 0, 37 y 100. Inicial 45; omisión/null conservan 45; números se aplican; estados
  esperados se mantienen. No son prueba de commit PostgreSQL ni autoridad normativa.
  Script y resultado: /workspace/work/m8/audit/AuditProgressProbe.java y probe.log.
- La ejecución previa clean verify: 174 suites, 2.020 contabilizadas, 2.014 aprobadas,
  seis omitidas y cero fallos/errores. No se reejecuta ni se presenta como una ejecución
  nueva: desde entonces el código productivo y los tests permanecen iguales.

Las pruebas demuestran la implementación escogida y su regresión; no convierten una
decisión interna en obligación del contrato. La homologación bilateral sigue pendiente.

## Diff productivo exacto

```diff
diff --git a/src/main/java/com/reclamos/backend/service/TicketStatusUpdateService.java b/src/main/java/com/reclamos/backend/service/TicketStatusUpdateService.java
index e7812df..8b0d561 100644
--- a/src/main/java/com/reclamos/backend/service/TicketStatusUpdateService.java
+++ b/src/main/java/com/reclamos/backend/service/TicketStatusUpdateService.java
@@ -130,6 +130,9 @@ public class TicketStatusUpdateService {
         switch (request.updateType()) {
             case STARTED -> {
                 requireCurrentStatus(ticket, TicketStatus.ROUTED, "STARTED sólo es válido con el ticket en ROUTED");
+                if (request.progress() != null) {
+                    ticket.setCurrentProgress(request.progress().shortValue());
+                }
                 newStatus = TicketStatus.IN_PROGRESS;
                 activityType = ActivityType.STATE_CHANGED;
             }
@@ -193,6 +196,9 @@ public class TicketStatusUpdateService {
                 if (isBlank(request.publicMessage())) {
                     throw new InvalidTicketRequestException("RESOLVED requiere publicMessage");
                 }
+                if (request.progress() != null) {
+                    ticket.setCurrentProgress(request.progress().shortValue());
+                }
                 newStatus = TicketStatus.RESOLVED;
                 activityType = ActivityType.RESOLVED;
                 resolutionType = resolution.type();
```

HEAD sin cambios: 326bb272c7f60e04c85474ecd6d3b79ccb8fcac6, rama dev.
Sin commits ni push. Sólo se actualizó documentación para cerrar el pendiente del
DOCX y corregir la calificación normativa anterior.
