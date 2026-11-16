# ADR-C12-004 State GOF para el ciclo de vida de Ticket

Fecha: 15/11/2026. Estado: **implementado para revisión local**, sin commit ni despliegue.

**Contexto y problema.** Las decisiones de transición estaban distribuidas entre revisión, derivación, información, resolución, cancelación, duplicados e integración. `TicketStatus` representaba estados persistidos, pero no delegaba comportamiento. La cátedra exige State sobre la entidad central y pruebas de transiciones inválidas con HTTP 409.

**Decisión adoptada.** `Ticket` delega en su `TicketState`, resuelto desde `currentStatus` en cada operación. El enum interno tiene comportamiento polimórfico: `IN_REVIEW` decide gestión propia/externa según el área; `PENDING_INFORMATION` restaura `resumeStatus`. `Ticket.transition()` aplica el destino y cambia `statusChangedAt` sólo al cambiar de estado. `TicketAction` identifica operaciones internas, sin incorporarse a contratos.

**Por qué es State GOF.** Las constantes especializadas sobrescriben `next()` y deciden destinos efectivos, incluidos los dependientes del contexto. Después de una transición, la siguiente operación se delega al nuevo estado. No hay un switch central por `TicketStatus`. Se conserva su persistencia JPA y el setter para inicialización/hidratación y fixtures; los servicios transicionan mediante el contexto.

**Clases involucradas.** Nuevas: `entity/TicketState`, `entity/TicketAction`. Contexto: `entity/Ticket`. Colaboradores: `TicketService`, `TicketResolutionService`, `TicketStatusUpdateService`, `InformationRequestService`, `InformationRequestExpirationService`, `ResolutionConfirmationTimeoutService`, `DuplicateTicketService`, `TicketReclassificationService`, `TicketCancellationRequestService` y `inbox/CoreInboxProcessor`.

**Responsabilidades conservadas.** Los servicios retienen permisos, payload, historial, solicitudes/propuestas, alcance, selección del duplicado, SLA, prioridad, locks, transacciones y efectos en su orden anterior. `canPerform()` consulta elegibilidad; no concede permisos. Se conservan excepciones, mensajes y códigos HTTP. Inbox comparte elegibilidad, conservando deduplicación, antigüedad, área, `PREMATURE_PROGRESS` y ACK/NACK, incluida la auditoría previa de rechazo externo con duplicados.

**Matriz funcional resumida.** Las acciones no mencionadas se rechazan. CLASSIFY sólo consulta elegibilidad en IN_REVIEW; ACCEPT_DUPLICATE sólo en los cinco estados activos de principal, sin cambiar estado ni fecha.

| Estado | Operaciones y destino | Guardas y efectos que conserva el servicio |
|---|---|---|
| REGISTERED | Revisar → IN_REVIEW; cancelar owner/staff → CANCELLED; vincular → DUPLICATE | Autoridad/propiedad; FIRST_RESPONSE se completa al revisar; duplicados exigen revisión de alcance |
| IN_REVIEW | Derivar → IN_PROGRESS (M2) o ROUTED (externa); pedir información → PENDING_INFORMATION; cancelar staff; vincular | Área y clasificación válidas, sin propuesta abierta; REQUEST_TYPE_MISMATCH exige propuesta autorizada; finalización y Outbox originales |
| ROUTED | STARTED → IN_PROGRESS; INFORMATION_REQUIRED → PENDING_INFORMATION; RETURNED → IN_REVIEW; RESOLVED → RESOLVED; REJECTED → CANCELLED; pedido local y vinculación | Productor/actor/payload válidos; resolución directa externa permitida; rechazo con duplicados requiere revisión; sin evento espejo |
| IN_PROGRESS | Resolución manual → RESOLVED; PROGRESS mantiene estado/fecha; restantes acciones externas como ROUTED salvo STARTED; pedido local y vinculación | Resolución manual sólo M2; avance, resultado, mensajes, SLA y confirmación conservados |
| PENDING_INFORMATION | Responder → resumeStatus; cancelar staff; vincular; vencimiento → CANCELLED o revisión → IN_REVIEW; abandono de pedido de reclasificación → IN_REVIEW | Una solicitud activa; respuesta completa/en plazo; pausa/reanudación de RESOLUTION; vencimiento con duplicados conserva revisión administrativa |
| DUPLICATE | Cierre heredado → CLOSED; cancelación GLOBAL → CANCELLED; promoción → IN_REVIEW | Sin cancelación directa owner; locks y principal válido; promoción del más antiguo, sin reabrir clasificación/FIRST_RESPONSE, nuevo RESOLUTION |
| RESOLVED | Confirmación o timeout → CLOSED; reapertura → IN_PROGRESS | Propiedad/plazo del job; propagación de cierre; reapertura incrementa contador y crea RESOLUTION |
| CLOSED / CANCELLED | Sin transiciones | Estado terminal |

**Alternativas consideradas.** Comparaciones distribuidas: duplicación y ausencia de State. Nueve clases con interfaz/factoría: archivos innecesarios para estados sin datos mutables. Tabla de transiciones: validación de relaciones sin comportamiento propio. Se eligió el enum polimórfico, sin dependencias ni abstracciones adicionales.

**Ventajas y consecuencias.** Destinos y elegibilidad centralizados, reutilizados por HTTP e Inbox, sin cambiar almacenamiento ni orden de efectos. La matriz parametrizada cubre nueve estados y 24 acciones; las pruebas HTTP verifican 409 sin efectos. Las suites existentes contrastan SLA, prioridad, contratos, Inbox/Outbox, concurrencia y rollback. Se conserva el gate LINE del 85%.

**Limitaciones y discrepancia preservada.** Las guardas con repositorios siguen en servicios. El setter permite técnicamente usos futuros incorrectos. Los textos describen INFO_TIMEOUT generalmente como cancelación; el código y sus pruebas contemplan IN_REVIEW con duplicados activos. Se preserva esa excepción; queda pendiente aclarar la documentación funcional.

**Evidencia.** `src/test/java/com/reclamos/backend/entity/TicketStateTest.java` contiene la matriz exhaustiva; `service/TicketStatePostgresIntegrationTest.java` prueba rechazos por HTTP con PostgreSQL y snapshots de efectos. Las pruebas preexistentes de cancelación, reclasificación, resolución, SLA, prioridad, contratos e Inbox/Outbox mantienen sus expectativas.
