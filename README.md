# Backend M2 — Atención Ciudadana

Backend con Spring Boot, PostgreSQL 17 y Flyway. El contrato REST versionado está en
[`src/main/resources/static/openapi.yaml`](src/main/resources/static/openapi.yaml).

## Quick Start único: backend y PostgreSQL con Docker (Windows / PowerShell)

Objetivo G1: desde prerrequisitos listos hasta la primera autenticación en **15 minutos o menos**.
La descarga inicial depende de la conexión y del equipo; no es una garantía de duración.
Consultar la evidencia y las limitaciones en [ADM-04](docs/adm-04-g1-validation.md).
Ejecutar los bloques en orden, en la **misma ventana de PowerShell**, desde una carpeta privada
local (sin sincronización pública). No hacen falta Java, Maven, PostgreSQL, pgAdmin, M1,
RabbitMQ ni una cuenta de IA en el host. Java 21 y Maven Wrapper se ejecutan dentro del build Docker.
Este recorrido está escrito para Windows; no se ofrece un segundo recorrido Bash parcial.

### 1. Prerrequisitos listos

- Windows 11 con WSL 2 habilitado y Docker Desktop 4.30 o posterior, abierto, usando **Linux containers**.
- Git 2.43 o posterior y **PowerShell 7.4 o posterior** (`pwsh`, no Windows PowerShell 5.1).
- Docker Engine accesible y **Compose v2.24 o posterior**, con soporte de `up --wait`.
- Internet hacia GitHub, Docker Hub y Maven Central, espacio para imágenes/build y volúmenes
  (reservar 5 GB libres) y al menos 4 GB de RAM asignados a Docker.
- Puertos locales 8080 y 5432 libres, o elegir alternativas en el paso 3.

Comprobar (todos deben terminar sin error):

```powershell
$PSVersionTable.PSVersion
git --version
docker version
docker compose version
```

### 2. Clonar master y copiar la configuración

Empezar a medir **antes del clonado**, con los prerrequisitos anteriores ya listos:

```powershell
$ErrorActionPreference = 'Stop'
$g1Timer = [System.Diagnostics.Stopwatch]::StartNew()
git clone --branch master --single-branch https://github.com/JuanmaGuida/Backend-DAMII.git
if ($LASTEXITCODE -ne 0) { throw 'Falló el clonado' }
Set-Location Backend-DAMII
if (Test-Path -LiteralPath .env) { throw '.env ya existe: conservarlo; no repetir la inicialización' }
Copy-Item -LiteralPath .env.example -Destination .env -ErrorAction Stop
# Restringir el archivo al usuario actual antes de escribir secretos.
$owner = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
icacls .env /inheritance:r /grant:r "${owner}:(F)" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'No se pudo restringir .env; no generar secretos' }
git check-ignore .env
if ($LASTEXITCODE -ne 0) { throw '.env debe estar ignorado antes de continuar' }
```

`git check-ignore` debe mostrar únicamente `.env`. `.gitignore` y `.dockerignore` excluyen
este archivo del versionado y del contexto del build. No usar `git add -f .env`.
Si ya existe la carpeta de un intento anterior, continuar allí con su `.env` original;
no volver a copiar la plantilla ni regenerar material persistente.

### 3. Generar secretos locales una sola vez

Este bloque sólo acepta los siete campos obligatorios **vacíos** de la plantilla. Si detecta
cualquier valor existente, se detiene sin escribir. No imprime contraseñas ni clave.
Genera seis valores independientes con el generador criptográfico del sistema: una clave
AES-256 de **32 bytes**, codificada como Base64 canónico (44 caracteres, padding `=`),
y cinco contraseñas locales con la misma entropía. El ID `local-v1` no es secreto y cumple
`[A-Za-z0-9._-]{1,64}`.

```powershell
$envPath = Join-Path (Get-Location).Path '.env'
$required = @(
    'POSTGRES_PASSWORD', 'TRACKING_CODE_ENCRYPTION_KEY_BASE64',
    'TRACKING_CODE_ENCRYPTION_KEY_ID', 'MOCK_CITIZEN_PASSWORD',
    'MOCK_AGENT_PASSWORD', 'MOCK_AREA_RESPONSIBLE_PASSWORD', 'MOCK_ADMIN_PASSWORD'
)
$text = [System.IO.File]::ReadAllText($envPath)
foreach ($name in $required) {
    if ([regex]::Matches($text, "(?m)^${name}=\r?$").Count -ne 1) {
        throw 'Inicialización cancelada: faltan campos vacíos o ya hay secretos. Conservar .env existente.'
    }
}
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    foreach ($name in $required) {
        if ($name -eq 'TRACKING_CODE_ENCRYPTION_KEY_ID') {
            $value = 'local-v1'
        } else {
            $bytes = [byte[]]::new(32)
            $rng.GetBytes($bytes)
            $value = [Convert]::ToBase64String($bytes)
            [Array]::Clear($bytes, 0, $bytes.Length)
        }
        $text = [regex]::Replace($text, "(?m)^${name}=\r?$", "${name}=${value}")
    }
    [System.IO.File]::WriteAllText($envPath, $text, [System.Text.UTF8Encoding]::new($false))
} finally {
    $rng.Dispose()
    $value = $null
    $text = $null
    $bytes = $null
}
```

Conservar **la misma clave y el mismo ID junto con la base**, incluso después de recrear
contenedores, reiniciar o restaurar respaldos. El ID identifica la clave: no permite recuperarla.
No sustituirla por una clave de tests/CI. No hay rotación ni recifrado implementados; cambiar
cualquiera de esos dos valores impide recuperar códigos anteriores. Respaldar `.env` de forma
privada y cifrada junto con la estrategia de respaldo de datos. Más detalles en la
[guía de cifrado](docs/tracking-code-encryption.md).

La plantilla ya deja los ajustes necesarios para trabajar sin servicios externos:

| Variable | Valor local |
| --- | --- |
| `POSTGRES_DB`, `POSTGRES_USER` | `reclamos`, `reclamos` (no son secretos) |
| `POSTGRES_PASSWORD` | Generada por el bloque anterior |
| `POSTGRES_PORT`, `APP_PORT` | `5432`, `8080` |
| `MOCK_*_PASSWORD` (cuatro) | Generadas por el bloque anterior |
| `TRACKING_CODE_ENCRYPTION_KEY_BASE64`, `TRACKING_CODE_ENCRYPTION_KEY_ID` | Generada, `local-v1` |
| `IDENTITY_PROVIDER` | `MOCK` |
| `SIMULATOR_ENABLED`, `AI_ENABLED` | `false`, `false` |
| `CORE_INBOX_ENABLED`, `CORE_OUTBOX_ENABLED`, `CORE_OUTBOX_AUTO_APPROVE_NEW_EVENTS` | Todos `false` |
| `CORE_OUTBOX_APPROVED_EVENT_IDS` | Vacío |
| `APP_JWT_SECRET`, `CORE_RABBIT_USERNAME`, `CORE_RABBIT_PASSWORD`, `AI_API_KEY` | Vacíos; no se requieren |

Compose fija el perfil `dev` y pasa datasource, cifrado, passwords MOCK, simulador e IA.
Actualmente **no reenvía** las variables de M1/Core de `.env`: usa los defaults seguros de
`application.properties` (`MOCK`, Inbox/Outbox apagados). Esos valores en `.env` documentan
la intención; este Compose es para el arranque local, no para activar integraciones.
`SIMULATOR_ENABLED=false` también es el default de `application-dev.properties`.

Para cambiar puertos, editar sólo `APP_PORT` o `POSTGRES_PORT` en `.env` con un editor local
privado (por ejemplo `notepad .env`). No copiar su contenido a chats ni capturas.
La conexión interna sigue siendo `postgres:5432`; no cambiar el datasource para otro puerto del host.

### 4. Validar y arrancar ambos servicios

Evitar variables heredadas que sobrescriban silenciosamente `.env` y validar Compose sin
mostrar la configuración resuelta:

```powershell
foreach ($line in [System.IO.File]::ReadAllLines($envPath)) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=') {
        $name = $Matches[1]
        if ([Environment]::GetEnvironmentVariable($name, 'Process')) {
            throw "Variable heredada ${name}: quitarla de esta terminal antes de continuar"
        }
    }
}
docker compose --env-file .env config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Configuración incompleta; revisar nombres de variables, sin mostrar valores' }
docker compose --env-file .env up -d --build --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw 'Arranque incompleto: ver solución de errores abajo' }
```

El build inicial descarga JDK/JRE y dependencias. Flyway aplica automáticamente las migraciones
antes de servir peticiones; Hibernate valida el esquema. `--wait` espera ambos healthchecks:
PostgreSQL usa `pg_isready`; backend exige HTTP 200 en `/actuator/health` dentro del contenedor.
El límite de 180 s aplica a la espera de servicios, no a toda la descarga/compilación.

### 5. Comprobar salud, Flyway y Swagger

```powershell
docker compose --env-file .env ps
# Leer configuración sólo en memoria; nunca mostrar $local ni el contenido de .env.
$local = @{}
foreach ($line in [System.IO.File]::ReadAllLines($envPath)) {
    if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $local[$Matches[1]] = $Matches[2] }
}
$baseUrl = "http://localhost:$($local['APP_PORT'])"
$health = Invoke-RestMethod -Uri "$baseUrl/actuator/health"
if ($health.status -ne 'UP') { throw 'Health no está UP' }
$health.status
$swagger = Invoke-WebRequest -Uri "$baseUrl/swagger-ui.html"
$contract = Invoke-WebRequest -Uri "$baseUrl/openapi.yaml"
if ($swagger.StatusCode -ne 200 -or $contract.StatusCode -ne 200) { throw 'Swagger no está disponible' }
docker compose --env-file .env exec -T postgres psql -U reclamos -d reclamos -c 'SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;'
if ($LASTEXITCODE -ne 0) { throw 'No se pudo verificar Flyway' }
```

Esperado: los dos servicios `healthy`, health `UP`, Swagger y contrato HTTP 200,
y última migración **53**, `success = t` (`V53__resource_revisions.sql`). El comando SQL usa
los nombres predeterminados; ajustarlos si se cambiaron en `.env`.
Abrir `http://localhost:8080/swagger-ui.html` en el navegador (adaptar `APP_PORT`).

### 6. Autenticar los cuatro usuarios y verificar sus roles

| Usuario | Variable de contraseña | Rol | Área |
| --- | --- | --- | --- |
| `citizen@example.test` | `MOCK_CITIZEN_PASSWORD` | `CITIZEN` | null |
| `agent@example.test` | `MOCK_AGENT_PASSWORD` | `AGENT` | null |
| `area.responsible@example.test` | `MOCK_AREA_RESPONSIBLE_PASSWORD` | `AREA_RESPONSIBLE` | M6 |
| `module.admin@example.test` | `MOCK_ADMIN_PASSWORD` | `ADMIN` | null |

El siguiente bloque envía `POST /api/auth/login` con JSON `{username,password}` y usa el token
en `GET /api/auth/me`. Sólo muestra rol y tiempo; **no mostrar la respuesta de login**, su token,
las cabeceras de autorización ni el cuerpo enviado. No habilitar transcripciones, verbose,
trazas HTTP, logging de cuerpos o bindings JDBC.

```powershell
$users = @(
    @('citizen@example.test', 'MOCK_CITIZEN_PASSWORD', 'CITIZEN', $null),
    @('agent@example.test', 'MOCK_AGENT_PASSWORD', 'AGENT', $null),
    @('area.responsible@example.test', 'MOCK_AREA_RESPONSIBLE_PASSWORD', 'AREA_RESPONSIBLE', 'M6'),
    @('module.admin@example.test', 'MOCK_ADMIN_PASSWORD', 'ADMIN', $null)
)
$firstLogin = $true
foreach ($user in $users) {
    $body = @{ username = $user[0]; password = $local[$user[1]] } | ConvertTo-Json -Compress
    try {
        $session = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $body
        $headers = @{ Authorization = "Bearer $($session.token)" }
        $me = Invoke-RestMethod -Uri "$baseUrl/api/auth/me" -Headers $headers
    } catch { throw 'Falló la autenticación MOCK; revisar configuración local sin imprimir credenciales' }
    if ($session.identity.role -ne $user[2] -or $me.role -ne $user[2] -or $me.areaId -ne $user[3]) {
        throw 'Rol o área MOCK inesperados'
    }
    if ($firstLogin) {
        $g1Timer.Stop()
        Write-Output ('Primera autenticación: {0:N1} segundos' -f $g1Timer.Elapsed.TotalSeconds)
        $firstLogin = $false
    }
    Write-Output ("Login y /me OK: " + $me.role)
}
$session = $null
$headers = $null
$body = $null
$local = $null
```

Los tokens MOCK son opacos, duran 8 horas y viven en memoria: al reiniciar hay que autenticarse
otra vez. No son credenciales productivas de M1. Para probar operaciones en Swagger, ejecutar
el login desde su formulario privado, usar **Authorize** con el token recibido (sin anteponer
`Bearer` si el formulario ya indica ese esquema), y no compartir capturas/respuestas.

### 7. Detener y volver a arrancar sin perder datos ni claves

```powershell
# Guardar una huella en memoria, sin mostrar material secreto.
$envFingerprint = (Get-FileHash -LiteralPath .env -Algorithm SHA256).Hash
docker compose --env-file .env down
if ($LASTEXITCODE -ne 0) { throw 'No se pudo detener' }
docker compose --env-file .env up -d --wait --wait-timeout 180
if ($LASTEXITCODE -ne 0) { throw 'No se pudo volver a arrancar' }
if ((Get-FileHash -LiteralPath .env -Algorithm SHA256).Hash -ne $envFingerprint) { throw '.env cambió' }
docker compose --env-file .env ps
docker compose --env-file .env exec -T postgres psql -U reclamos -d reclamos -c 'SELECT version, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;'
```

`down` conserva `postgres_data` y `attachment_data`; `up` vuelve a levantar **ambos** servicios.
Mantener la carpeta/nombre de proyecto Compose, el `.env` y los volúmenes. Para un reinicio
rápido también sirve `docker compose --env-file .env restart`; luego comprobar salud y repetir
los pasos 5 y 6 para obtener tokens nuevos. Los registros creados deben seguir disponibles.
Los volúmenes no son un respaldo: guardarlos junto con una copia privada de la clave/ID.
**No ejecutar `down -v`, `volume prune` ni borrar volúmenes** salvo que se quiera descartar
explícitamente todos los datos y adjuntos locales.

### Solución de errores

| Síntoma | Acción |
| --- | --- |
| Docker daemon inaccesible | Abrir Docker Desktop, esperar Engine listo y seleccionar Linux containers; repetir `docker version`. |
| `Set ... in .env` / variable requerida vacía | Verificar que se está en la raíz, con `.env` y los siete campos del paso 3. Compose valida incluso al iniciar sólo PostgreSQL. No pegar valores en errores ni ejecutar `docker compose config` sin `--quiet`. |
| Generador rechaza `.env` existente | Conservarlo. No borrar ni regenerar la clave de una base existente. Recuperar el respaldo privado si falta material; el ID solo no alcanza. |
| Clave inválida al iniciar | Debe decodificar a 32 bytes y recodificar exactamente igual a Base64 canónico, sin espacios; ID de 1–64 caracteres permitidos. No inventar una nueva clave para una base persistente. |
| Puerto 5432 ocupado | En `.env`, usar `POSTGRES_PORT=5433`; repetir `up -d --wait`. No cambiar la URL interna `postgres:5432`. |
| Puerto 8080 ocupado | Usar `APP_PORT=8081`, repetir `up -d --wait`; usar 8081 en navegador y recalcular `$baseUrl` con el paso 5. |
| Falló descarga/build | Revisar conectividad/proxy hacia Docker Hub y Maven Central, espacio y RAM. Repetir el mismo `up --build`; conservar `.env` y volúmenes. |
| Timeout / unhealthy | Ejecutar `ps`, comprobar Docker/recursos y repetir `up -d --wait --wait-timeout 180`. Consultar logs sólo en el equipo privado; no publicar logs sin revisión. |
| PostgreSQL rechaza contraseña | Las credenciales se aplican al crear el volumen por primera vez. Restaurar los valores originales del `.env`; cambiarlos no cambia la contraseña de una base ya inicializada. No borrar datos para resolverlo automáticamente. |
| Login 401 | Usar el username exacto y contraseña de su variable; comprobar perfil `dev`. Después de reiniciar, obtener un token nuevo. |
| SQL/Flyway falla | Verificar que se usa la base local correcta. No editar migraciones aplicadas ni ejecutar `repair` como solución automática. |
| Un cambio en `.env` no se aplica | `restart` no relee variables. Usar `up -d --force-recreate --wait`, conservando claves y volúmenes. Resolver primero variables heredadas del shell. |

No usar `docker inspect`, `docker compose config` sin `--quiet`, volcados de entorno ni `curl -v`
como diagnóstico compartido: pueden revelar secretos. Tampoco incluir `.env` en issues, reportes
ni artefactos. No activar logging de cuerpos/bindings: `show-sql` actual imprime SQL sin los valores
vinculados y `logServerErrorDetail=false` evita copiar detalles sensibles del driver PostgreSQL.

## Referencias de configuración y desarrollo

- [Cifrado persistente de códigos de seguimiento](docs/tracking-code-encryption.md).
- [`.env.example`](.env.example), [`compose.yml`](compose.yml), [`Dockerfile`](Dockerfile).
- [`application.properties`](src/main/resources/application.properties) y
  [`application-dev.properties`](src/main/resources/application-dev.properties).
- Flyway se ejecuta automáticamente desde `src/main/resources/db/migration`; la última
  migración es **V53**, no V41. La biblioteca actual es **Flyway 12.4.0**, administrada por Spring Boot 4.1.2 en `pom.xml`. No modificar migraciones históricas.
- Para desarrollo fuera de Docker sí se necesita **Java 21**. Maven/IDE no cargan `.env`
  automáticamente: proporcionar al proceso `SPRING_PROFILES_ACTIVE=dev`, datasource local,
  las cuatro passwords MOCK y la misma clave/ID persistente mediante configuración privada.
  `APP_PORT` sólo controla Docker. Este modo no forma parte del Quick Start G1.
- Tests: `./mvnw -B clean verify` (Windows: `.\mvnw.cmd -B clean verify`), sólo contra una
  base exclusiva de tests, con datasource y secretos de test explícitos; nunca la base persistente.

## Configuración de solicitudes de información

`TICKET_INFORMATION_RESPONSE_DURATION` controla el plazo de respuesta (default `72h`) y
`TICKET_INFORMATION_EXPIRATION_SCAN_DELAY` la revisión de vencimientos (default `60000` ms).
Se resuelven en `application.properties`; para un proceso Maven/IDE se pasan como variables
privadas del proceso. El Compose local no las reenvía actualmente.

## CI, coverage, GHCR y CD

Los pull requests ejecutan Java 21, PostgreSQL 17, `./mvnw -B clean verify`, generan y publican el reporte JaCoCo y validan el Dockerfile sin publicar imágenes. La cobertura LINE tiene un gate obligatorio de **85%**: un resultado inferior hace fallar `verify`. El porcentaje queda visible en el resumen de GitHub Actions y el reporte HTML se conserva como artifact.

Los pushes a `dev` publican imágenes privadas en `ghcr.io/juanmaguida/backend-damii` con `dev` y `sha-<commit-completo>`. El despliegue por SSH sólo se habilita cuando la variable de GitHub `CD_ENABLED` vale exactamente `true`; su configuración y operación se documentan en `docs/runbook.md` del repositorio Infra.

El CD no utiliza GitHub Environments. En `Settings → Secrets and variables → Actions` deben configurarse como **Repository Variables** `CD_ENABLED`, `LIGHTSAIL_HOST`, `LIGHTSAIL_USER`, `LIGHTSAIL_DEPLOY_PATH` y `LIGHTSAIL_KNOWN_HOSTS`; la clave dedicada se guarda únicamente como **Repository Secret** `LIGHTSAIL_SSH_PRIVATE_KEY`. Con `CD_ENABLED=false` la imagen GHCR se publica, pero el deploy automático se omite.


## Validación de release y secretos

Los pushes de tags `v*` (incluido `v2.0-entrega4`) validan el commit etiquetado;
no publican imágenes ni despliegan. Se conservan pruebas y gate de líneas del 85%.
JaCoCo, resultados JUnit, ref/SHA y un resumen de secretos sin valores son evidencia
para QA. La duración definitiva se mide en el run del tag cuando se cierre la release.
Sonar y los demás controles académicos pendientes no están implementados por este cambio.

Desde la raíz: `python .github/scripts/check-secrets.py` (Python 3.11+ y Git).
Descarga temporalmente Gitleaks 8.30.1 oficial, verifica SHA-256 y escanea todo el
historial alcanzable por las referencias locales más archivos actuales versionables,
incluidos untracked no ignorados. Requiere clon completo; no descarga refs por su cuenta.
Para ejecución sin descarga: `python .github/scripts/check-secrets.py --gitleaks <ruta>`.
Agregar `--self-test` ejecuta regresiones reales con valores inventados y commits sólo
en repositorios temporales; es obligatorio en el job G6 de CI.
`--output <archivo-fuera-del-repo.json>` guarda sólo regla/ruta/líneas/commit y recuentos.
Los `.env` privados ignorados quedan fuera: revisar `git status` antes de agregar archivos.
No publicar reportes crudos de Gitleaks, logs, tokens ni contraseñas.

La configuración conserva las reglas oficiales y detecta asignaciones literales en
código, JSON, YAML, `.env`, properties y formatos de configuración, incluidos defaults
de variables y finales LF/CRLF. Omite referencias y placeholders completos, con letras
Unicode. Las fixtures revisadas (stubs, proveedores de test, transacciones de prueba,
CI efímero y ejemplos negativos) requieren valor exacto, regla y ruta anclada al archivo
desde la raíz. Se conservan las fixtures públicas ya verificadas. Valores nuevos, copias
en subdirectorios y otros tipos de secretos en esas rutas se siguen detectando.
El runner rechaza `.gitleaksignore` y descuenta después del escaneo sólo los 82
fingerprints exactos de `.gitleaks-history-exceptions.json` (commit completo, archivo,
regla y línea), aprobados para siete contraseñas históricas. El inventario no contiene
sus valores. Cualquier entrada inválida, duplicada o sin coincidencia provoca un error;
el árbol actual nunca recibe esas excepciones. El test de CI fija cantidad y huella
del inventario: cambiarlo requiere una revisión explícita. Se informa cuántos hallazgos
se detectaron y exceptuaron, sin publicar contenido sensible.
Esta aceptación técnica conserva los secretos en el historial y no acredita G6 literal
ante la cátedra. Su vigencia/reutilización sigue pendiente de confirmación con el equipo;
si hubo uso real corresponde coordinar la rotación. No se rota ni reescribe automáticamente.
La detección está limitada a los patrones y formatos reconocidos; no es cobertura absoluta.
Publicar y desplegar requiere que G6 termine correctamente, además de los gates existentes.
Un fallo, cancelación u omisión de G6 bloquea ambas operaciones; no afecta la versión ya desplegada.

La auditoría de INT-05, la matriz ACK/NACK, el runbook de reprocesamiento y los
pendientes de homologación con Core están en
[INT-05 — Inbox y reprocesamiento](docs/integrations/int-05-inbox-y-reprocesamiento.md).
