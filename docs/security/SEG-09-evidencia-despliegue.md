# SEG-09 (DDA2-262): evidencia de cabeceras sobre el despliegue

Criterio de finalización del ítem: `curl -I` contra el despliegue muestra los headers esperados.

- **Host:** `https://atencion-ciudadana.ddnsfree.com`
- **Fecha y hora (secciones 1 a 3, estado previo al fix):** lunes 23/11/2026, 18:15 UTC (15:15 hora de Buenos Aires). La repetición posterior al despliegue está en la sección 4.
- **Comandos** (PowerShell, Windows; `--ssl-no-revoke` por el chequeo de revocación de schannel):

```
curl.exe -sSI --ssl-no-revoke https://atencion-ciudadana.ddnsfree.com/
curl.exe -sSI --ssl-no-revoke https://atencion-ciudadana.ddnsfree.com/api/catalog/categories
curl.exe -sSI --ssl-no-revoke -H "Origin: https://evil.example" https://atencion-ciudadana.ddnsfree.com/api/auth/me
```

## 1. Front (`/`)

```
HTTP/1.1 200 OK
Content-Security-Policy: default-src 'self'; script-src 'self' https://www.googletagmanager.com; style-src 'self' https://fonts.googleapis.com; style-src-elem 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data: blob: https://server.arcgisonline.com https://cdnjs.cloudflare.com https://raw.githubusercontent.com https://www.googletagmanager.com https://*.google-analytics.com; connect-src 'self' https://nominatim.openstreetmap.org https://www.googletagmanager.com https://*.google-analytics.com https://www.google.com; frame-src blob: https://www.openstreetmap.org; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'
Referrer-Policy: strict-origin-when-cross-origin
Server: nginx/1.30.7
Strict-Transport-Security: max-age=86400
Via: 1.1 Caddy
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
```

## 2. API, HEAD a un endpoint público (`/api/catalog/categories`)

```
HTTP/1.1 200 OK
Cache-Control: no-cache, no-store, max-age=0, must-revalidate
Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'
Referrer-Policy: no-referrer
Referrer-Policy: strict-origin-when-cross-origin
Strict-Transport-Security: max-age=86400
X-Content-Type-Options: nosniff
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-Frame-Options: DENY
X-Xss-Protection: 0
```

## 3. API, origen ajeno sobre un endpoint autenticado (`/api/auth/me`)

```
HTTP/1.1 401 Unauthorized
Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'
Content-Type: application/problem+json
Referrer-Policy: no-referrer
Referrer-Policy: strict-origin-when-cross-origin
Strict-Transport-Security: max-age=86400
Www-Authenticate: Bearer
X-Content-Type-Options: nosniff
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
X-Frame-Options: DENY
```

No aparece `Access-Control-Allow-Origin` ni `Access-Control-Allow-Credentials`: el origen ajeno no recibe permiso CORS.

(Se omitieron de este resumen `Date`, `Content-Length`, `Etag`, `Last-Modified`, `Expires`, `Pragma`, `Accept-Ranges` y `Content-Type` cuando no aportan al criterio.)

## 4. Repetición después de desplegar el fix de `/api/`

- **Fecha y hora:** lunes 23/11/2026, aprox. 19:41 UTC (16:41 hora de Buenos Aires).
- **Despliegue:** front `main`, commit `c68c1ae6b0d68ee25d4d755b9a2373038605263f` (`proxy_hide_header` de las tres cabeceras en `location /api/`), imagen `sha256:d8b88dc7ea36aa916d1bd9e039c92fbf400aefe8734baa8a88e8cc5a1ef48b89`. Fuente del commit y del digest: el job `Deploy Frontend to Lightsail` ([run 45238696530](https://github.com/SantiMussi/front-desarrollo-apps-2/actions/runs/45238696530)). No se contrastó contra el contenedor en ejecución: lo que prueba qué está sirviendo el host son las cabeceras de abajo.
- **Comandos** (PowerShell; GET, no HEAD; salida filtrada a las cabeceras de seguridad):

```
curl.exe --ssl-no-revoke -s -D - -o NUL https://atencion-ciudadana.ddnsfree.com/api/catalog/categories | Select-String "referrer-policy|x-content-type|x-frame|content-security|strict-transport"
curl.exe --ssl-no-revoke -s -D - -o NUL "https://atencion-ciudadana.ddnsfree.com/api/staff/tickets?page=0&size=1" | Select-String "referrer-policy|x-content-type|x-frame|content-security|strict-transport"
curl.exe --ssl-no-revoke -s -D - -o NUL https://atencion-ciudadana.ddnsfree.com/ | Select-String "referrer-policy|x-content-type|x-frame|content-security|strict-transport"
```

- **Limitación:** el filtro no incluía la línea de estado, así que esta repetición **no registra los códigos HTTP** (el catálogo es público y `/api/staff/tickets` sin token debería dar 401, pero no quedó capturado).

`/api/catalog/categories` y `/api/staff/tickets?page=0&size=1` (salida idéntica en las dos rutas):

```
Content-Security-Policy: default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'
Referrer-Policy: strict-origin-when-cross-origin
Strict-Transport-Security: max-age=86400
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
```

`/` (portal): `Content-Security-Policy` sin cambios respecto de la sección 1, `Referrer-Policy: strict-origin-when-cross-origin`, `Strict-Transport-Security: max-age=86400`, `X-Content-Type-Options: nosniff` y `X-Frame-Options: DENY`, una sola vez cada una.

Resultado: en `/api/` ya sale **una** ocurrencia de `Referrer-Policy`, `X-Content-Type-Options` y `X-Frame-Options`. Subsiste solo el valor del nginx (`strict-origin-when-cross-origin`); el `no-referrer` del backend queda oculto por el proxy. CSP y HSTS se mantienen, y el portal no cambió.

## Lectura

| Criterio | Resultado |
|---|---|
| CSP | Presente en el front y en la API. |
| HSTS | Presente, `max-age=86400`, una sola vez. Lo emite Caddy; nginx descarta el del backend (`proxy_hide_header`). Es un valor corto a propósito: el sitio vive en un dominio de DDNS y un valor largo vuelve difícil revertir un problema de certificado. |
| `X-Content-Type-Options: nosniff` | Presente. En la API salía duplicado en la captura de las 15:15; en la repetición de las 16:41 (sección 4) sale una sola vez. |
| CORS sin comodín | Cumple: no se concede `Access-Control-Allow-Origin` a un origen ajeno. **Alcance: same-origin.** No se habilita ningún origen cruzado a propósito: el front llega a la API por el mismo origen (`location /api/` del nginx del front, detrás de Caddy) y `SecurityConfiguration` no configura CORS, así que nunca hay `Access-Control-Allow-Origin` ni comodín con credenciales. Ningún cliente de otro origen está permitido por contrato; por eso la comprobación es de rechazo y no de lista de orígenes permitidos. |
| Cookies Secure/HttpOnly/SameSite | **No verificable con `curl`; verificada en el navegador.** La cookie de sesión `__Host-M2-Refresh` se define con Secure, HttpOnly y SameSite=Strict en `M1SessionCookies`, que solo existe con el proveedor de identidad M1; los tres `M1SessionCookiesTest` lo verifican en prueba. **Observada en el despliegue** (23/11/2026, ~16:49 hora de Buenos Aires, DevTools → Application → Cookies, tras un login de ciudadano que pasa por M1): `__Host-M2-Refresh` con `HttpOnly` y `Secure` tildados, `SameSite=Strict`, `Path=/`, sin dominio explícito (host-only) y vencimiento el 30/11/2026. Lo observado coincide con lo que define `M1SessionCookies`. No se adjunta captura con el valor porque es un token de refresco. Las cookies `_ga` y `_ga_*` de Google Analytics no son `Secure` ni `HttpOnly`; no son de M2. |

## Hallazgos abiertos

1. **Cabeceras duplicadas en `/api/**`** (`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`): **corregido en el despliegue; falta el retest del QA.** Causa: las `add_header` del `server{}` del `nginx.conf` del front se heredan al `location /api/` y el backend envía las suyas; `Referrer-Policy` salía con dos valores distintos. Corrección: `proxy_hide_header` de las tres en `location /api/` (front `c68c1ae`). La sección 4 muestra una sola ocurrencia por cabecera, aunque sin registrar los códigos HTTP de esa repetición.
2. **CSP del front** con `'unsafe-inline'` en `style-src-elem` y con Google Tag Manager en `script-src`. Está justificado en el propio `nginx.conf` (hojas de estilo dinámicas de Motion y analítica); un escáner lo va a marcar.
3. **HSTS de un día** frente al año que emite el backend (`SecurityConfiguration`, verificado por `SecurityHeadersTest`): el valor del backend nunca llega al navegador.
