# Backend M2 - Atención Ciudadana

Backend del **Módulo 2 - Atención Ciudadana**, desarrollado con **Spring Boot**, **PostgreSQL**, **Docker** y **Flyway**.

Este documento explica cómo configurar y ejecutar el proyecto en un entorno local.

---

# Requisitos previos

Para ejecutar el proyecto se requiere:

* **Java 21**
* **Docker Desktop**

> **Docker Desktop debe estar abierto y ejecutándose antes de levantar PostgreSQL.**

PostgreSQL se ejecuta mediante Docker, por lo que no es necesario tener un servidor PostgreSQL instalado localmente.

Opcionalmente puede utilizarse **pgAdmin** para visualizar la base de datos y ejecutar consultas SQL.

---

# Configuración del entorno

## Crear el archivo `.env`

En la raíz del proyecto se encuentra:

```text
.env.example
```

Crear una copia llamada:

```text
.env
```

Contenido base:

```env
# Copiar este archivo como .env para personalizar el entorno local.
# Estos valores son solo para desarrollo y no deben usarse en producción.

POSTGRES_DB=reclamos
POSTGRES_USER=reclamos
POSTGRES_PASSWORD=reclamos_local
POSTGRES_PORT=5432
APP_PORT=8080
```

### Variables disponibles

| Variable            | Función                                              |
| ------------------- | ---------------------------------------------------- |
| `POSTGRES_DB`       | Nombre de la base de datos                           |
| `POSTGRES_USER`     | Usuario de PostgreSQL                                |
| `POSTGRES_PASSWORD` | Contraseña local                                     |
| `POSTGRES_PORT`     | Puerto de PostgreSQL expuesto en la máquina          |
| `APP_PORT`          | Puerto del backend cuando se ejecuta mediante Docker |

El archivo `.env` contiene configuración local y **no debe subirse al repositorio**.

---

# Formas de ejecutar el proyecto

Existen dos formas principales.

## Opción A — Recomendada para desarrollo

```text
PostgreSQL → Docker
Backend    → IDE / Maven
```

Esta modalidad permite desarrollar y reiniciar el backend sin necesidad de reconstruir su imagen Docker.

## Opción B — Ejecutar todo con Docker

```text
PostgreSQL → Docker
Backend    → Docker
```

---

# Opción A — PostgreSQL con Docker + backend local

## 1. Abrir Docker Desktop

Docker Desktop debe encontrarse ejecutándose.

## 2. Levantar PostgreSQL

Desde la raíz del proyecto:

```bash
docker compose up -d postgres
```

Si el contenedor inicia correctamente, PostgreSQL aparecerá como **Running** en Docker Desktop.

---

# Configuración local de Spring Boot

El `application.properties` utiliza variables de entorno para conectarse a PostgreSQL:

```properties
spring.datasource.url=${SPRING_DATASOURCE_URL}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD}
```

Al ejecutar el backend localmente desde cualquier IDE, estas variables deben configurarse en el entorno de ejecución:

```text
SPRING_PROFILES_ACTIVE=dev
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/reclamos
SPRING_DATASOURCE_USERNAME=reclamos
SPRING_DATASOURCE_PASSWORD=reclamos_local
```

Los valores deben coincidir con los configurados en `.env`.
Si se utiliza otro puerto para PostgreSQL, también debe modificarse `SPRING_DATASOURCE_URL`.

---

# Profile de desarrollo

Para desarrollo local se utiliza:

```text
SPRING_PROFILES_ACTIVE=dev
```

Este profile habilita la implementación mock de identidad utilizada mientras M2 no está integrado con M1.

La configuración específica se encuentra en:

```text
src/main/resources/application-dev.properties
```

---

# Ejecutar el backend

Una vez que PostgreSQL esté corriendo y las variables de entorno hayan sido configuradas, ejecutar:

```text
BackendApplication.java
```

desde el IDE utilizado.

También puede ejecutarse mediante Maven Wrapper.

### Windows

```powershell
.\mvnw.cmd spring-boot:run
```

### Linux / macOS

```bash
./mvnw spring-boot:run
```

Por defecto, la API estará disponible en:

```text
http://localhost:8080
```

---

# Verificar que el backend esté funcionando

El proyecto expone el endpoint de health de Spring Boot Actuator:

```text
http://localhost:8080/actuator/health
```

Resultado esperado:

```json
{
  "status": "UP"
}
```

---

# Flyway

La estructura y evolución de la base de datos se administran mediante **Flyway**.

Las migraciones se encuentran en:

```text
src/main/resources/db/migration
```

Por ejemplo:

```text
V1__...
V2__...
V3__...
V4__...
V5__...
```

Las migraciones se ejecutan automáticamente al iniciar el backend.
No deben ejecutarse manualmente ni modificarse migraciones que ya hayan sido aplicadas y compartidas con el equipo.

Flyway registra las migraciones ejecutadas en:

```text
flyway_schema_history
```

Puede verificarse mediante:

```sql
SELECT
    installed_rank,
    version,
    description,
    success
FROM flyway_schema_history
ORDER BY installed_rank;
```

---

# Conectar pgAdmin

Este paso es opcional.

Registrar un nuevo servidor utilizando los datos configurados en `.env`.

Con la configuración predeterminada:

```text
Name: Reclamos Local

Host: localhost
Port: 5432
Database: reclamos
Username: reclamos
Password: reclamos_local
```

Si se modifica `POSTGRES_PORT`, utilizar también ese puerto en pgAdmin.

---

# Acceder a PostgreSQL desde Docker

También puede accederse directamente a PostgreSQL sin utilizar pgAdmin:

```bash
docker compose exec postgres psql -U reclamos -d reclamos
```

Para verificar la conexión:

```sql
SELECT current_user, current_database();
```

Para salir:

```text
\q
```

---

# Configuración de solicitudes de información

El proyecto permite configurar el plazo disponible para responder solicitudes de información adicional.

En `application.properties`:

```properties
ticket.information-request.response-duration=${TICKET_INFORMATION_RESPONSE_DURATION:72h}
ticket.information-request.expiration-scan-delay=${TICKET_INFORMATION_EXPIRATION_SCAN_DELAY:60000}
```

Valores predeterminados:

```text
Plazo de respuesta: 72 horas
Revisión de vencimientos: cada 60 segundos
```

Si se desea modificarlas localmente:

```text
TICKET_INFORMATION_RESPONSE_DURATION=48h
TICKET_INFORMATION_EXPIRATION_SCAN_DELAY=30000
```

---

# Usuarios mock disponibles en desarrollo

El profile `dev` incluye identidades simuladas para desarrollar sin depender de M1.

## Ciudadano

```text
Username: citizen@example.test
Password: CitizenDev!2026
Role: CITIZEN
Area ID: null
```

## Agente

```text
Username: agent@example.test
Password: AgentDev!2026
Role: AGENT
Area ID: null
```

## Responsable de área

```text
Username: area.responsible@example.test
Password: AreaDev!2026
Role: AREA_RESPONSIBLE
Area ID: M6
```

## Administrador de M2

```text
Username: module.admin@example.test
Password: AdminDev!2026
Role: ADMIN
Area ID: null
```

Los tokens generados por las identidades mock se mantienen únicamente en memoria y se eliminan al reiniciar el backend.

---

# Ejecutar todo mediante Docker

El proyecto también permite levantar PostgreSQL y el backend mediante Docker.

Ejecutar:

```bash
docker compose up -d --build
```

Si ambos contenedores se inician correctamente, aparecerán como **Running** en Docker Desktop.

La aplicación estará disponible en:

```text
http://localhost:8080
```

o en el puerto configurado mediante:

```env
APP_PORT
```

---

# Detener el proyecto

Para detener los contenedores:

```bash
docker compose down
```

Los datos de PostgreSQL se conservan en el volumen de Docker.

Para volver a utilizar el proyecto:

```bash
docker compose up -d postgres
```

---

# Reiniciar la base de datos desde cero

Si se necesita eliminar completamente la base local:

```bash
docker compose down -v
```

Luego:

```bash
docker compose up -d postgres
```

> ⚠️ Esto elimina todos los datos locales de PostgreSQL.

Las migraciones volverán a ejecutarse la próxima vez que se inicie el backend.

---

# Flujo recomendado

Una vez configurado el entorno por primera vez:

### 1. Abrir Docker Desktop

### 2. Levantar PostgreSQL

```bash
docker compose up -d postgres
```

Comprobar en Docker Desktop que el contenedor se encuentre **Running**.

### 3. Ejecutar el backend

Ejecutar:

```text
BackendApplication.java
```

desde el IDE utilizado.

### 4. Desarrollar y probar

Utilizar Insomnia, Postman u otra herramienta para consumir:

```text
http://localhost:8080
```

---

# Troubleshooting

## El puerto 5432 está ocupado

Puede ocurrir que el puerto estándar de PostgreSQL ya esté siendo utilizado por otro proceso o por una instalación local de PostgreSQL.

En ese caso, modificar `.env`:

```env
POSTGRES_PORT=5433
```

Docker expondrá PostgreSQL mediante:

```text
localhost:5433
```

Si el backend se ejecuta localmente desde un IDE, también debe modificarse:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/reclamos
```

Y en pgAdmin:

```text
Host: localhost
Port: 5433
Database: reclamos
Username: reclamos
Password: reclamos_local
```

Después recrear el contenedor:

```bash
docker compose down
docker compose up -d postgres
```

---

## El puerto 8080 está ocupado

Si se ejecuta el backend mediante Docker y el puerto `8080` ya está siendo utilizado, modificar:

```env
APP_PORT=8081
```

Entonces la API estará disponible en:

```text
http://localhost:8081
```

> `APP_PORT` controla el puerto expuesto por Docker. Si Spring Boot se ejecuta localmente desde un IDE, esta variable no modifica automáticamente el puerto de Spring.

---

## Error de usuario o contraseña de PostgreSQL

Puede aparecer un error similar a:

```text
password authentication failed
```

Primero verificar que los valores utilizados por Spring coincidan con `.env`:

```env
POSTGRES_DB=reclamos
POSTGRES_USER=reclamos
POSTGRES_PASSWORD=reclamos_local
```

Existe un detalle importante con los volúmenes de Docker:

PostgreSQL utiliza estas variables para inicializar la base la primera vez que se crea el volumen.

Si posteriormente se modifica el usuario o la contraseña en `.env`, el volumen puede continuar utilizando los valores anteriores.

Si se pueden eliminar los datos locales, recrear la base:

```bash
docker compose down -v
docker compose up -d postgres
```

> ⚠️ `docker compose down -v` elimina todos los datos locales almacenados en PostgreSQL.

---

## PostgreSQL aparece como detenido o con errores

Revisar el contenedor `postgres` desde Docker Desktop.

Si es necesario, consultar sus logs directamente desde Docker Desktop.

También puede intentarse reiniciar el servicio:

```bash
docker compose restart postgres
```

---

# Resumen rápido

## Variables locales

```text
SPRING_PROFILES_ACTIVE=dev
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/reclamos
SPRING_DATASOURCE_USERNAME=reclamos
SPRING_DATASOURCE_PASSWORD=reclamos_local
```

## Levantar PostgreSQL

```bash
docker compose up -d postgres
```

## Ejecutar backend

Ejecutar:

```text
BackendApplication.java
```

desde el IDE utilizado.

## Ejecutar tests

```powershell
.\mvnw.cmd clean test
```

## Si el puerto 5432 está ocupado

`.env`:

```env
POSTGRES_PORT=5433
```

Configuración local de Spring:

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5433/reclamos
```
