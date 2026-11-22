# Recuperación cifrada del código de seguimiento

El seguimiento público valida el código ingresado contra `tracking_code_hash` (SHA-256).
Al crear un ticket identificado se guarda también una copia del código original cifrada con
AES-256-GCM. Su propietario autenticado puede recuperarlo desde el detalle en Mis reclamos.
Los tickets anónimos y los históricos sin copia cifrada conservan el seguimiento por hash.

## Configuración obligatoria

Backend valida al arrancar:

- `TRACKING_CODE_ENCRYPTION_KEY_BASE64`: Base64 canónico de exactamente 32 bytes aleatorios.
- `TRACKING_CODE_ENCRYPTION_KEY_ID`: identificador estable `[A-Za-z0-9._-]{1,64}`, por ejemplo `dev-v1`.

Ambas se suministran al proceso Java/IDE o al `.env` ignorado usado por Compose. No hay clave
por defecto ni generación al iniciar. El arranque completo falla si falta alguna o es inválida.
Los valores vacíos de `.env.example` son intencionales: hay que aprovisionar una clave antes de usarlo.
Para Maven local, usar las variables del proceso; Maven no carga `.env` automáticamente.

Generar una vez por entorno/base y conservar clave e ID en reinicios, recreaciones y despliegues.
Guardar una copia privada fuera del host, protegida por el mecanismo habitual de secretos del equipo.
Quien pueda leer entorno Docker/host y base puede descifrar: restringir acceso a ambos.
No utilizar la clave sintética del workflow o de tests en DEV persistente.

Para Windows / PowerShell, seguir el [Quick Start único del README](../README.md#3-generar-secretos-locales-una-sola-vez):
copia la plantilla al `.env` ignorado, restringe su ACL y genera clave, ID y passwords locales
sin imprimirlos. El bloque rechaza cualquier configuración ya inicializada sin modificarla.
No repetirlo para reinicios, recreaciones ni restauraciones; conservar el material original.
Para Maven/IDE, transferir los valores mediante configuración privada del proceso.
No usar `.env` como `build-arg`, variable Vite, contenido de imagen ni `images.env`.
En Lightsail seguir la guía específica de Infra; no ejecutar la preparación Windows en el servidor.

## Persistencia y formato v1

V46 añade tres columnas nullable: ciphertext `BYTEA` de 48 bytes (incluye tag), nonce `BYTEA`
de 12 bytes y keyId `VARCHAR(64)`. CHECK exige todo-null o todo-presente y sólo admite material
en identificados. Un índice único parcial por keyId/nonce rechaza colisiones persistidas.
Los hashes y sus restricciones se conservan. No se edita ninguna migración histórica.

AAD UTF-8 canónica: `M2:tracking-code:v1|<ticket UUID>|<citizen UUID>|<keyId>`.
Se usa `AES/GCM/NoPadding`, tag de 128 bits y un `Cipher` independiente por operación.
El UUID lo asigna Hibernate al persistir; se cifra antes del primer flush explícito y dentro
de la misma transacción. Cualquier error impide confirmar el ticket y sus efectos asociados.
No se introduce escritura de credenciales en eventos, notificaciones ni DTOs generales.

## API privada

`GET /api/me/tickets/{ticketId}/tracking-code`, con Bearer del propietario.
La búsqueda exige `id`, `citizenId` y ticket no anónimo. CITIZEN, AGENT, ADMIN y
AREA_RESPONSIBLE sólo acceden a sus propios tickets. Los permisos staff no amplían ese acceso.

Respuesta 200 mínima: `{"trackingCode":"<código original>"}`. Nunca poner el código en URL.
Respuestas no almacenables (`Cache-Control: no-store`). No se regenera código ni se escriben
actividades, eventos o notificaciones. Se registra un resultado técnico sin credenciales.

| Situación | HTTP/código |
| --- | --- |
| Sin autenticación válida | 401 INVALID_TOKEN |
| UUID inválido | 400 INVALID_REQUEST |
| Ajeno, anónimo o inexistente | 404 NOT_FOUND uniforme |
| Propio sin material recuperable | 409 TRACKING_CODE_NOT_RECOVERABLE |
| Material alterado, clave incompatible o hash incoherente | 503 TRACKING_CODE_UNAVAILABLE |

La cadena de autenticación existente puede sincronizar perfil ModuleUser; la consulta no añade
escrituras de negocio. El descifrado verifica tag/AAD y después el hash original. No se devuelve
texto parcial ni se revela la causa criptográfica al cliente. `POST /api/tracking/access` no cambia.

No activar logging de bodies/bindings JDBC. El driver tiene `logServerErrorDetail=false` para
evitar que errores PostgreSQL copien filas con credenciales a los logs de aplicación.
Los logs/respaldos del propio servidor PostgreSQL también deben permanecer restringidos.

## Tests y CI

Las integraciones requieren PostgreSQL real **exclusivo de tests**, datasource explícito, las
passwords mock y ambas variables de cifrado. Los workflows usan material sintético de test.
No apuntar las suites a la base DEV compartida. Ejecutar con Java 21:

```text
mvnw.cmd -B clean verify
```

Incluye migración V45→V46 con históricos, JPA, creación y recuperación, ownership por rol,
rollback, manipulación, concurrencia, exclusión de secretos y recreación lógica del cifrador.
El gate JaCoCo LINE del 85% se mantiene. El build Docker no necesita una clave runtime.

## Despliegue y reversión

Antes de cualquier push a dev con posibilidad de CD: deshabilitar `CD_ENABLED`, esperar jobs
pendientes y preparar la clave persistente y el Compose remoto. Sólo después publicar/desplegar
Backend. Ver `Infra/docs/tracking-code-encryption.md` para pasos del operador.
No se requiere cambiar Dynu, Caddy, Nginx, certificados ni `/api`.

Ante rollback conservar columnas y clave; la imagen antigua acepta columnas extra y puede crear
tickets identificados sin material recuperable. Esas altas se tratarán como históricas.
La clave no se cambia al restaurar una imagen. Perderla o sustituirla impide recuperar material
anterior; el tracking por hash continúa funcionando. `keyId` identifica la clave, no la recupera.
No reutilizar la misma clave bajo otros IDs. Rotación/recifrado requieren una operación futura
específica que conserve claves anteriores; no están implementados.
