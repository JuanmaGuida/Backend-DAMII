# INT-05: Inbox idempotente, mensajes fallidos y reprocesamiento

Describe cómo M2 consume los eventos del Core, qué hace con los duplicados y con los mensajes que fallan, qué queda registrado y cómo se reprocesa. Documenta el comportamiento implementado en `CoreEventConsumer` y `CoreInboxProcessor`.

**Alcance y límite.** La cola es `q.atencion-ciudadana`. El retry diferido y la DLQ pertenecen al **Core**: M2 solo rechaza el mensaje y no cambia esa topología. Los valores efectivos de retry/DLQ del Core real **no están acreditados** (ver "Limitaciones").

## Qué hace M2 con cada mensaje

M2 confirma (ACK) solo después de que la transacción hizo commit. Rechazar es `basicNack(tag, false, false)`: no reencola en M2, y el mensaje sigue el camino de retry/DLQ del Core.

| Caso | Efecto en M2 | Registro | Mensaje |
|---|---|---|---|
| Evento nuevo y válido | Se aplica | Fila en `inbox_events`, `PROCESSED`, con `disposition` en `payload` | ACK |
| Mismo `eventId`, mismo tipo y mismo hash, ya procesado | Sin efectos (se procesa una sola vez) | Ninguno nuevo | ACK |
| Mismo `eventId` con tipo o hash distintos | No se aplica | Log `code=EVENT_ID_COLLISION`, sin fila | NACK |
| `eventId` ya existente en otro estado | No se aplica | Log `code=INVALID_EVENT`, sin fila | NACK |
| Rechazo de negocio (`InvalidTicketRequestException`, `TicketStateConflictException`) | Rollback del efecto | Fila `FAILED` con `error=REJECTED_BUSINESS_RULE`, en una transacción nueva y sin datos personales; log `code=REJECTED_BUSINESS_RULE` | ACK después de que la fila hace commit |
| Falla al grabar esa fila de rechazo | Rollback | Log `code=REJECTION_AUDIT_FAILED` | NACK |
| `PROGRESS` con el ticket todavía en `ROUTED` | No se aplica | Log `code=PREMATURE_PROGRESS`, sin fila definitiva | NACK |
| Sobre, esquema, versión o productor inválidos | No se aplica | Log con `INVALID_EVENT`, `UNSUPPORTED_VERSION` o `WRONG_PRODUCER`, sin fila | NACK |
| Error de base de datos o de commit | Rollback | Log `code=PROCESSING_FAILED`, sin fila | NACK |
| Otra área, ticket desconocido, hecho antiguo, estado incompatible o productor ignorado | No se aplica | Fila `PROCESSED` con `disposition=IGNORED_*` | ACK |
| Tipo de evento distinto de `updateTicketStatus` y `citizenUpdated` | Ignorado | Log `IGNORED_OTHER_EVENT` con el `eventId`, sin fila | ACK |

La idempotencia se apoya en `inbox_events.event_id` (clave primaria) y en un bloqueo por `eventId` antes de decidir.

## Qué queda registrado

- **En la base:** `inbox_events` guarda `event_id`, `event_type`, `producer_module_id`, `status`, `error`, `received_at`, `processed_at` y metadatos en `payload`. No guarda datos personales ni mensajes privados.
- **En el log:** los fallos se registran solo con un código (`Core Inbox code=...`). Es deliberado: el cuerpo del mensaje puede contener datos personales.
- **Consecuencia:** un mensaje que M2 rechaza con NACK **no deja una fila en M2**, y su log no incluye el `eventId`. Para identificarlo hay que cruzar el horario del log con el contenido de la DLQ del Core.

## Cómo reprocesar

M2 no reprocesa por sí mismo. Según la documentación del Core que usa este proyecto (Eventos M2 v1.79, resumida en [`m2-m4.md`](m2-m4.md#acknack-e-idempotencia)), el Core deduplica antes de rutear: **reenviar el mismo `eventId` no recupera una entrega agotada**. La recuperación es desde la DLQ del Core:

1. Identificar el mensaje en la DLQ del Core (`GET /api/v1/dlq`). Requiere autorización de operación del Core.
2. Corregir en M2 la causa y comprobar en los logs que ya no aparece el código de fallo. Ejemplos: la base estaba caída, o el `PROGRESS` llegó antes de que se procesara el `STARTED`.
3. Pedir el reintento al Core (`POST /api/v1/dlq/{id}/retry`). Requiere la misma autorización.
4. Verificar en M2 que quedó procesado:

```sql
SELECT event_id, status, error, processed_at, payload->>'disposition' AS disposition
FROM inbox_events
WHERE event_id = '<uuid del evento>';
```

El estado esperado es `PROCESSED`. Si el Core entrega el mismo evento dos veces, la segunda entrega termina en ACK sin repetir efectos.

**Casos que no se reprocesan:**

- `FAILED` con `REJECTED_BUSINESS_RULE`: M2 ya lo confirmó. Si el productor quiere que se aplique, tiene que emitir un evento nuevo con otro `eventId`.
- `EVENT_ID_COLLISION`: es un error del productor, que reutilizó un `eventId`. Reintentar el mismo mensaje vuelve a colisionar; hay que corregirlo en origen con un `eventId` nuevo.

## Evidencia automatizada

| Prueba | Qué acredita |
|---|---|
| `CoreInboxPostgresIntegrationTest` | Con PostgreSQL real (en CI): deduplicación, rollback, origen y `PROGRESS` prematuro |
| `CoreEventConsumerRabbitIntegrationTest` | Con RabbitMQ efímero real: ACK/NACK. Usa un procesador simulado y una DLQ de fixture |
| `CoreEventConsumerTest`, `CoreIncomingEventValidatorTest` | Reglas del consumidor y validación de eventos, sin broker |

## Limitaciones

- **No acreditado con el Core real:** que un mensaje inválido o que falla llegue a la DLQ del Core, la topología y los valores efectivos de retry/DLQ, y el reproceso con `POST /api/v1/dlq/{id}/retry`. Las pruebas con broker usan una DLQ de fixture, no la del Core compartido.
- **No acreditado:** negocio y broker integrados en una misma prueba, ni la recuperación del broker durante el procesamiento del Inbox.
- **Sin persistencia de los NACK:** ver "Qué queda registrado".
- **Para cerrarlo:** con identidades y autorización reales, ejecutar la verificación 7 de [`m2-m4.md`](m2-m4.md) (repetir `eventId`, colisionarlo, `PROGRESS` prematuro, y comprobar ACK/NACK y retry/DLQ reales con `GET /api/v1/topology` y `GET /api/v1/dlq`), y guardar la evidencia sanitizada con fecha y correlación.
