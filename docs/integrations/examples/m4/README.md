# Fixtures contractuales M4 v1.2 RC

Fuente: `Contrato_Integral_Eventos_M4 al 02-11-2026.pdf`, portada v1.2 RC del 02/11/2026.

- `routed-original.json`: transcripción JSON de §5.1, sin cambiar valores ni campos.
- `resolved-original.json`: transcripción JSON de §4.8; attachments es vacío en el original.
- `routed-adaptado.json`: conserva todos los valores originales y añade las claves que
  emite el snapshot actual de M2: causationId=null, progress=null, publicMessage=null,
  routing.location=null, routing.resolutionDueAt=null y escalation inactivo con sus
  subcampos nulos. Los valores de plazo/ubicación reales se calculan al derivar; los
  nulos de esta fixture son ilustrativos, admitidos por el schema.

Sólo se normalizó la indentación de las transcripciones y se retiraron los pies de
página intercalados por el PDF. No se copió su pie editorial antiguo v1.1 como versión
contractual: portada e historial son v1.2 RC. Los tres JSON validan contra schemas locales.
M2 v1.79 §13.1 mantiene el paquete v1.78; no se modifican schemas ni política de nulos.
La adaptación no corrige un error del original: sus campos adicionales son opcionales.

`M4DocumentationContractTest` valida originales y adaptación; `TicketOutboxServiceTest`
usa la clasificación/identificadores de §5.1 al generar ROUTED y compara sus campos de
negocio. `CoreInboxPostgresIntegrationTest` procesa §4.8 cambiando sólo eventId/ticketId
para aislar el ticket de prueba; otras pruebas cubren evidencias no vacías y deduplicación.

Estos ejemplos y pruebas no acreditan comunicación real con M4. No publicar fixtures
ficticias con credenciales reales. Para pruebas bilaterales usar UUID/actor/catalogo,
publicId, fechas y URL autorizados del entorno, con el mismo ticketId durante el flujo.
