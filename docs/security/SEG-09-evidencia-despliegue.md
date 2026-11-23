# SEG-09 (DDA2-262): evidencia de cabeceras sobre el despliegue

Criterio de finalización del ítem: `curl -I` contra el despliegue muestra los headers esperados.

- **Host:** `https://atencion-ciudadana.ddnsfree.com`
- **Fecha y hora:** lunes 23/11/2026, 18:15 UTC (15:15 hora de Buenos Aires)
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

## Lectura

| Criterio | Resultado |
|---|---|
| CSP | Presente en el front y en la API. |
| HSTS | Presente, `max-age=86400`, una sola vez. Lo emite Caddy; nginx descarta el del backend (`proxy_hide_header`). Es un valor corto a propósito: el sitio vive en un dominio de DDNS y un valor largo vuelve difícil revertir un problema de certificado. |
| `X-Content-Type-Options: nosniff` | Presente. En la API sale duplicado. |
| CORS sin comodín | Cumple: no se concede `Access-Control-Allow-Origin` a un origen ajeno. |
| Cookies Secure/HttpOnly/SameSite | **No verificado con estos `curl`.** La cookie de sesión `__Host-M2-Refresh` se define con Secure, HttpOnly y SameSite=Strict en `M1SessionCookies`, que solo existe con el proveedor de identidad M1; los tres `M1SessionCookiesTest` lo verifican en prueba. Falta observarla en el despliegue tras un login. |

## Hallazgos abiertos

1. **Cabeceras duplicadas en `/api/**`** (`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`). Causa: las `add_header` del `server{}` del `nginx.conf` del front se heredan al `location /api/` y el backend envía las suyas. `Referrer-Policy` sale con dos valores distintos. Corrección preparada en el front (`proxy_hide_header` de las tres en `location /api/`); **no está desplegada**, así que esta evidencia muestra el estado previo. Hay que repetir los `curl` después del despliegue.
2. **CSP del front** con `'unsafe-inline'` en `style-src-elem` y con Google Tag Manager en `script-src`. Está justificado en el propio `nginx.conf` (hojas de estilo dinámicas de Motion y analítica); un escáner lo va a marcar.
3. **HSTS de un día** frente al año que emite el backend (`SecurityConfiguration`, verificado por `SecurityHeadersTest`): el valor del backend nunca llega al navegador.
