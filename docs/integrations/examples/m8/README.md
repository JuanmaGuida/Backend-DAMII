# Ejemplos M2 ↔ M8: procedencia y límites

Fuente recibida: `EventosConEnvelopeM8V3.pdf`, 21 páginas.
SHA-256: `fa3c7d19e53e004418f01ca139d99a89bd619a803ffa0e55c6b16554625e5784`.
Extracción con `pdftotext -layout`. No se incorporan eventos M8 destinados a M1/M5.

`*-original.txt` conserva el bloque extraído del PDF, incluidos los saltos dentro de
cadenas. Se quitan únicamente pies numéricos y separadores de página fuera del JSON;
no es una reproducción binaria del PDF. `*-normalizado.json` reindenta el mismo
objeto y sustituye por un espacio cada salto de línea dentro de una cadena.
No cambia IDs, enums, mensajes, fechas, campos, null ni valores. Es una normalización
editorial identificada, **no se presenta como JSON literal original**.

| Ejemplo | Páginas | Original parseable y válido contra schema completo | Copia normalizada |
| --- | --- | --- | --- |
| STARTED | 4 | Sí | Válida |
| PROGRESS | 4–5 | No: salto dentro de publicMessage | Válida |
| INFORMATION_REQUIRED | 5–6 | Sí | Válida estructuralmente |
| RETURNED | 6–7 | No: salto dentro de internalMessage | Válida |
| RESOLVED / REQUEST_FULFILLED | 7–8 | No: salto dentro de publicMessage | Válida |
| RESOLVED / NO_FURTHER_ACTION_REQUIRED | 8–9 | Sí | Válida |
| REJECTED | 9–10 | Sí | Válida |
| ticketUpdated / ROUTED | 19–21 (cierre en 21) | Sí | Válida |

Resultados automatizados en M8DocumentationContractTest, con formatos habilitados y
schemas v1.78 sin modificar. No se encontraron campos faltantes obligatorios,
adicionales ni enums inválidos en los ocho objetos normalizados. La ausencia de
causationId y progress en ROUTED está permitida; no se inventan campos obligatorios.
Las siete respuestas conservan ticketId/correlationId del ROUTED y su eventId como
causationId. Son ejemplos independientes: el orden del PDF no define una secuencia.

Los archivos conservan las fechas de agosto de 2026. En noviembre requiredBy original
está vencido: pasar schema **no significa** pasar reglas temporales de negocio.
CoreInboxPostgresIntegrationTest.m8Fixture carga copias normalizadas y adapta sólo:
eventId único, ticketId desechable, occurredAt/updateOccurredAt al reloj de procesamiento;
para INFORMATION_REQUIRED, requiredBy = reloj + 1 hora. No escribe esos cambios en
fixtures. Conserva mensajes, actores null, razones, porcentajes y trazas ilustrativas.
No hay evidencia de un ROUTED real detrás de esas trazas en esos tests.
La respuesta con fecha pasada se prueba separadamente como rechazo de negocio con
rollback y ACK auditado. Los escenarios compartidos son sintéticos locales y no
representan recepciones de M8 ni validación de identidad por Core.
