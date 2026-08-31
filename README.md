# Backend M2 - Atención Ciudadana

Backend del Módulo 2 de Atención Ciudadana. Requiere Java 21 y PostgreSQL.

## Identidad mock de M1 para DEV

El backend incluye un proveedor de identidad en memoria para desarrollar M2 sin depender todavía de Módulo 1. `MockIdentityProvider` es exclusivamente una herramienta de DEV: no es autenticación productiva y sus credenciales nunca deben utilizarse fuera del entorno mock.

El mock sólo se registra con el profile explícito `dev`. No existe un profile DEV predeterminado ni un fallback a usuarios ficticios. Si la aplicación arranca sin `dev` y no hay otra implementación de `IdentityProvider`, falla al iniciar en lugar de habilitar silenciosamente el mock.

Los dos archivos Compose del proyecto ya activan `SPRING_PROFILES_ACTIVE=dev`. Para ejecutar directamente con Maven desde PowerShell:

```powershell
$env:SPRING_PROFILES_ACTIVE="dev"
.\mvnw.cmd spring-boot:run
```

La duración de sesión se configura en `application-dev.properties` mediante `app.identity.mock.session-ttl`. Su valor actual es `PT8H` (ocho horas).

### Usuarios disponibles

| Representación | Username | Password DEV | subjectId | citizenId | areaId | Roles internos M2 |
|---|---|---|---|---|---|---|
| Ciudadano autenticado | `citizen@example.test` | `CitizenDev!2026` | `m1-dev-citizen` | `10000000-0000-0000-0000-000000000001` | — | `[]` |
| Operador / Call Center | `agent@example.test` | `AgentDev!2026` | `m1-dev-agent` | `10000000-0000-0000-0000-000000000002` | `M2` | `[AGENT]` |
| Responsable de área | `area.responsible@example.test` | `AreaDev!2026` | `m1-dev-area-responsible` | `10000000-0000-0000-0000-000000000003` | `M6` | `[AREA_RESPONSIBLE]` |
| Supervisor | `supervisor@example.test` | `SupervisorDev!2026` | `m1-dev-supervisor` | `10000000-0000-0000-0000-000000000004` | `M2` | `[SUPERVISOR]` |
| Auditor | `auditor@example.test` | `AuditorDev!2026` | `m1-dev-auditor` | `10000000-0000-0000-0000-000000000005` | `M2` | `[AUDITOR]` |
| Administrador M2 | `module.admin@example.test` | `AdminDev!2026` | `m1-dev-module-admin` | `10000000-0000-0000-0000-000000000006` | `M2` | `[MODULE_ADMIN]` |

Todos los `citizenId` son UUID externos de M1. El ciudadano normal tiene deliberadamente roles internos vacíos: su capacidad de actuar como ciudadano deriva de estar autenticado y poseer `citizenId`, no de un rol artificial `CITIZEN`. `AGENT` representa tanto al operador de atención como al agente de Call Center.

`areaId` utiliza el mismo namespace de módulos/áreas que `responsibleAreaId`, por ejemplo `M2` o `M6`. `null` indica que la identidad no está asociada a un área operativa; `M2` identifica empleados de Atención Ciudadana y `M6` representa el módulo operativo externo usado por el fixture `AREA_RESPONSIBLE`.

### Login

```http
POST /api/auth/login
Content-Type: application/json

{
  "username": "agent@example.test",
  "password": "AgentDev!2026"
}
```

Respuesta abreviada:

```json
{
  "token": "TOKEN_OPACO",
  "tokenType": "Bearer",
  "expiresAt": "2026-08-31T03:00:00Z",
  "identity": {
    "subjectId": "m1-dev-agent",
    "citizenId": "10000000-0000-0000-0000-000000000002",
    "displayName": "Agente de prueba",
    "areaId": "M2",
    "roles": ["AGENT"]
  }
}
```

Un username inexistente y una password incorrecta producen la misma respuesta `401` con código `INVALID_CREDENTIALS`; el API no revela si el usuario existe.

### Consultar la sesión

```http
GET /api/auth/me
Authorization: Bearer TOKEN_OPACO
```

```json
{
  "subjectId": "m1-dev-agent",
  "citizenId": "10000000-0000-0000-0000-000000000002",
  "displayName": "Agente de prueba",
  "areaId": "M2",
  "roles": ["AGENT"]
}
```

Los tokens son aleatorios, opacos y se almacenan únicamente en memoria. Expiran después del TTL configurado y las sesiones expiradas se eliminan al resolver tokens o crear nuevas sesiones. Todas las sesiones desaparecen al reiniciar el backend. No hay refresh token, persistencia de sesiones ni soporte para múltiples réplicas.

### Sustitución futura por M1

Controllers y seguridad dependen de la interfaz neutral `IdentityProvider`, y los requests autenticados reciben un `AuthenticatedIdentity` como principal de Spring Security. La integración real deberá implementar `M1IdentityProvider` sobre esa misma interfaz y registrarla fuera del profile `dev`. Al retirar el mock no será necesario exponer sus usernames, passwords, tokens o almacenamiento de sesiones a la lógica de tickets.
