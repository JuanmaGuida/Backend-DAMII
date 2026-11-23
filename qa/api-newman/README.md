# Newman y contratos OpenAPI de M2

Las colecciones versionadas cubren las 97 operaciones publicadas con al menos un éxito y un error por cada método y ruta. La [matriz de casos](coverage.md) se genera desde el OpenAPI. En cada ejecución, coverage-executed.md acredita únicamente las respuestas HTTP que pasaron las aserciones y la validación del contrato. El job falla si falta alguno de los dos resultados de cualquier operación.

## Ejecución

Backend CI ejecuta el job Newman automáticamente después de validate. Descarga el JAR compilado, audita e instala el lock de Node 24 y comprueba la colección y la matriz versionadas. El artefacto backend-newman-results conserva ambos JUnit, los JSON, la matriz efectiva, la revisión, los hashes del JAR y OpenAPI y la duración. Los informes excluyen cuerpos, valores de cookies y tokens.

Para preparar o verificar los archivos desde la raíz:

~~~bash
npm ci --prefix qa/api-newman --ignore-scripts --no-audit --no-fund
npm run --prefix qa/api-newman generate
npm run --prefix qa/api-newman check:generated
~~~

Para reproducir el job, usar un JAR validado, un servicio Docker PostgreSQL 17 descartable en loopback con base y usuario m2_newman_qa, y configurar SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME y SPRING_DATASOURCE_PASSWORD para ese servicio. Pasar el identificador del contenedor y una carpeta de evidencia externa. RUNNER_TEMP debe apuntar a un directorio temporal escribible.

~~~bash
bash qa/api-newman/ci.sh /ruta/backend.jar "$POSTGRES_CONTAINER" "$RUNNER_TEMP/api-qa-results"
~~~

Usar una base nueva por corrida. Flyway instala el catálogo y el único fixture SQL prepara un conflicto que normalmente se origina en una expiración o respuesta externa. Los demás datos se crean por API. El grupo de sesión reinicia el mismo JAR con un M1 sintético en loopback y TLS con un certificado efímero confiado por esa JVM. La cookie se transporta sólo en el entorno local de QA. Core e IA externa están apagados.

El probe exige HTTP 200, media type JSON de Actuator o JSON estándar y estado UP. Antes de cada colección se compara el hash del OpenAPI publicado por el JAR con el YAML del checkout. El runner valida estado, media type y esquemas de las respuestas JSON. También compara el contenido binario con el PNG subido, comprueba que el CSV contiene cabecera y datos y exige cuerpo vacío en los 204. Las consultas SQL contrastan los efectos persistidos, incluido el reintento idempotente.

## Dependencias de QA

El lock de Newman 6.2.3 fija las dependencias transitivas corregidas. El SDK usa una API de Faker anterior para variables aleatorias. El runner reemplaza los generadores aleatorios en su propio proceso y exige fixtures explícitos. El lock completo se escanea con la política habitual.

~~~bash
python3 .github/scripts/scan-vulnerabilities.py --fs qa/api-newman --npm qa/api-newman --output "$RUNNER_TEMP/newman-sca"
~~~
