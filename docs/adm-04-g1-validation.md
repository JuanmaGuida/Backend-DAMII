# ADM-04: evidencia y limitaciones del arranque G1

**Criterio.** Alguien que no conoce el proyecto levanta backend y PostgreSQL en 15 minutos o menos usando solo el [README](../README.md).

## Qué hay

- El README tiene un recorrido único para Windows (PowerShell), con prerrequisitos, creación del `.env`, arranque con Docker Compose y primera autenticación.
- Las contraseñas MOCK, la clave de cifrado del código de seguimiento (`TRACKING_CODE_ENCRYPTION_KEY_BASE64`, 32 bytes aleatorios en Base64) y su identificador se generan en el `.env` por el propio recorrido. No se copian a mano.
- El recorrido incluye un cronómetro (`$g1Timer`) que al final imprime `Primera autenticación: N segundos`.
- No requiere Java, Maven, PostgreSQL, M1, RabbitMQ ni una cuenta de IA en el equipo: Java 21 y Maven corren dentro del build Docker.

## Qué NO está acreditado

- **No hay una ejecución cronometrada por una persona ajena al proyecto.** El QA revisó que el README documenta variables, clave y usuarios sintéticos, pero no tenía Docker disponible y no pudo hacer el arranque limpio (comentario del QA en DDA2-221, 17/11/2026).
- El recorrido cubre backend y PostgreSQL. No cubre M1, Core ni RabbitMQ; el arranque con broker está en el repositorio de infraestructura.
- La descarga inicial de imágenes depende de la conexión y del equipo; el objetivo de 15 minutos no es una garantía.

## Cómo se acredita

1. Una persona que no haya participado en el proyecto, en un Windows con Docker Desktop, parte de una carpeta nueva.
2. Sigue el README sin ayuda, en una sola ventana de PowerShell, y no abre otros documentos.
3. Se anota la línea que imprime el cronómetro y si pudo autenticarse.
4. Se completa la tabla de abajo con lo observado, incluidos los pasos donde se trabó.

| Fecha | Persona (ajena a M2) | SO y versión de Docker | Cronómetro | ¿Autenticó? | Observaciones |
|---|---|---|---|---|---|
| _pendiente_ | | | | | |

Mientras la tabla esté vacía, el criterio de ADM-04 no está cumplido.
