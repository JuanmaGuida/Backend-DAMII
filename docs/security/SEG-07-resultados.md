# SEG-07 — validación posterior al PR #17

**PASS para el alcance API REST JSON documentado.** Base: `b236690b75bde43ba311f1de79f572b1eac77f5b`, HEAD de origin/master después de git fetch origin --prune. El commit es el merge del PR #17. El patch anterior aplicó sin conflictos; se adaptaron únicamente documentación, evidencia y fingerprints de SEG-07.

| Análisis completo | High | Medium | Low | Informational |
| --- | ---: | ---: | ---: | ---: |
| Baseline | 0 | 2 | 66 | 3 |
| Activo autenticado | 0 | 0 | 0 | 0 |

Se cuentan instancias de alerta sin rebajar severidades ni suprimir categorías.
PR #17 incorporó CSP: el antiguo 10038 (CSP ausente) quedó resuelto por master, no por este patch. Su tratamiento se conserva como histórico. Ahora Swagger genera dos variantes distintas de 10055, registradas individualmente. Se incluyó alertRef en el fingerprint para evitar que ambas colisionaran.

## Tratamientos Medium actuales

### 10055-5 — CSP: script-src unsafe-inline

- Clave: `zap:5653:a0ce:1dc4:0f69:1526`; regla `10055`.
- Endpoint: `GET http://backend:8080/swagger-ui/index.html`.
- Severidad **Medium**, confianza **High**.
- Evidencia: `default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'`.
- Causa: PR #17 incorpora una CSP específica para Swagger UI con 'unsafe-inline' en script-src.
- Clasificación: real, no falso positivo. Estado: analizado y justificado para el laboratorio, sin corrección productiva aplicada.
- Justificación: Permite scripts inline, debilitando la defensa ante XSS. Hallazgo real de defensa en profundidad, no falso positivo. Justificación individual limitada al ensayo aislado: Swagger y contrato estáticos del mismo origen, sin datos reales ni contenido de usuarios en el HTML. La política está restringida a documentación, mientras los endpoints JSON mantienen default-src none. No se demostró explotación; se conserva Medium y no se afirma aceptación productiva.
- Corrección propuesta: Evaluar retirar unsafe-inline de script-src, conservar self y usar nonces/hashes para cualquier script inline necesario, verificando Try it out. Alternativa: deshabilitar documentación pública en producción. Propuesta sin aplicar para preservar lo incorporado en PR #17.

### 10055-6 — CSP: style-src unsafe-inline

- Clave: `zap:9a79:ffd2:639b:2d71:bb5f`; regla `10055`.
- Endpoint: `GET http://backend:8080/swagger-ui/index.html`.
- Severidad **Medium**, confianza **High**.
- Evidencia: `default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'`.
- Causa: PR #17 incorpora una CSP específica para Swagger UI con 'unsafe-inline' en style-src.
- Clasificación: real, no falso positivo. Estado: analizado y justificado para el laboratorio, sin corrección productiva aplicada.
- Justificación: Permite estilos inline, debilitando restricciones ante inyección CSS. Hallazgo real de defensa en profundidad, no falso positivo. Justificación individual limitada al ensayo aislado: Swagger y contrato estáticos del mismo origen, sin datos reales ni contenido de usuarios en el HTML. La política está restringida a documentación, mientras los endpoints JSON mantienen default-src none. No se demostró explotación; se conserva Medium y no se afirma aceptación productiva.
- Corrección propuesta: Evaluar nonces/hashes o adaptación de estilos inline generados por Swagger; verificar el renderizado antes de retirar unsafe-inline de style-src. Alternativa: deshabilitar documentación pública en producción. Propuesta sin aplicar para preservar lo incorporado en PR #17.

## Roles y tráfico efectivamente verificado

| Rol | Requests con Bearer esperado al backend | Estados HTTP | Checks /me |
| --- | ---: | --- | ---: |
| CITIZEN | 261 | {'200': 259, '400': 2} | 6 |
| AGENT | 393 | {'200': 389, '400': 3, '409': 1} | 8 |
| AREA_RESPONSIBLE | 378 | {'200': 374, '400': 3, '409': 1} | 7 |
| ADMIN | 30 | {'200': 30} | 4 |

Cada rol completó POST /api/auth/login, Bearer específico aplicado por Replacer, GET /api/auth/me antes/durante/después (sujeto y rol esperados, M6 para AREA_RESPONSIBLE), controles anónimo y token inválido 401 y auditoría en memoria de cada messageId. No se infiere sesión válida solamente del login. Los contadores excluyen intentos fuera de alcance bloqueados por guard.py, que se informan aparte. Las respuestas 400 corresponden a entradas de ataque; hubo tráfico activo 200 y control posterior de sesión válido.

- CITIZEN: `GET /api/me/tickets` (261).
- AGENT: `GET /api/tickets` (378), `GET /api/staff/labels` (15).
- AREA_RESPONSIBLE: `GET /api/staff/tickets/area` (378).
- ADMIN: `GET /api/admin/agents` (15), `GET /api/staff/labels` (15).

## Alcance y capacidades opcionales

46 reglas activas habilitadas; intensidad LOW y umbrales originales conservados. Exclusiones exactas, individuales y visibles en active-policy.json:

- 40026 DOM XSS: el navegador no arrancaba; la muestra protegida es JSON, sin ejecución DOM. Se retienen XSS reflejado/persistente. No se afirma haber hecho pruebas DOM en Swagger.
- 40043 Log4Shell OAST: no hay callback OAST seleccionado en la red aislada. El JAR de esta revisión usa Logback/bridge Log4j sin log4j-core (dependencies.json, incluido SHA del JAR). No se afirma cobertura OOB; reevaluar si cambian dependencias/alcance.
- 50000: dispatcher de scripts personalizados, sin scripts suministrados; se conservan las reglas incorporadas.

Cualquier otro Skipped/Pending, timeout, error, identidad incorrecta o Bearer ausente bloquea. Hay un hilo/host, demora 150 ms, límite 3 min/regla y 12 min/ruta. Los scans completaron sus reglas habilitadas; su progreso está adjunto. No se modificaron reglas productivas ni severidades para conseguir PASS.

Baseline: health, categorías, barrios, OpenAPI estático/generado, Swagger UI y cuatro recursos UI, todos GET 200. coverage de baseline registra paths y headers. Fuera del activo: otras operaciones del contrato (coverage-exclusions.json), escrituras de negocio, uploads, tracking por password, lógica por ID/IDOR exhaustivo, M1/Core/IA reales, notificaciones reales, cookies M1, TLS y ambientes compartidos. Cuatro identidades sintéticas en DB descartable; no datos reales.

## Reproducción y validación

```bash
python3 -m unittest discover -s security/seg07 -p 'test_*.py' -v
bash security/seg07/run.sh baseline
bash security/seg07/run.sh active
```

En la ejecución ZAP original pasaron 13 pruebas, incluida separación de variantes por alertRef, fallos ante evidencia incompleta y barrera HTTP. Ambos comandos se ejecutaron realmente sobre master actualizado y terminaron con éxito. Shell/Python/YAML validados. Workflow SEG-07 independiente con artifacts saneados incluso al fallar, sin continue-on-error. La evidencia original se capturó antes de publicar; no demuestra una ejecución de Actions.

Cero High y cada Medium actual tratado individualmente justifican PASS sólo en este alcance. Los Medium permanecen reales y visibles; las justificaciones del laboratorio no son aprobación de riesgo productivo. Los cambios productivos y demás workflows del PR #17 se preservan intactos.

Archivos exclusivamente SEG-07: security/seg07/, .github/workflows/seg07.yml y docs/security/. Reportes JSON/HTML, política efectiva, inventarios y hashes en seg07-evidence/. No se entregan logs crudos, sesiones, tokens, contraseñas, cookies ni archivos temporales. El checkout de publicación es /workspace/work/seg07-clean.

## Corrección del identificador de hallazgo y Gitleaks

Los reportes conservan las detecciones y autenticación de la ejecución original. Se migró únicamente el identificador público a `zap:hhhh:hhhh:hhhh:hhhh:hhhh`, conservando los 80 bits del digest. Los tratamientos y HTML usan el mismo identificador. No se repitió ZAP por este cambio de serialización.

Validación de publicación: 15 pruebas SEG-07 aprobadas; Gitleaks 8.30.1 con `--self-test` devuelve clean, exit 0, cero hallazgos actuales o históricos pendientes y 85/85 excepciones originales coincidentes. No se agregaron excepciones ni se modificó `.gitleaks.toml`. Las ramas remotas que retenían los hallazgos anteriores fueron retiradas por el equipo y las refs locales se actualizaron con fetch --prune.

La rama feature/seg-07-clean parte directamente del master b236690b75bde43ba311f1de79f572b1eac77f5b, sin cherry-pick ni ancestros de los commits anteriores de SEG-07. La comprobación usa todas las ramas y tags descargados por el CI. Las refs de PR antiguos sólo afectan a --all si se descargan explícitamente; refs/pull/18/head aún es visible remotamente, pero no forma parte del fetch de este PR nuevo. No se suprimieron referencias del scanner ni reglas de detección.

La evidencia vigente de preparación está en SEG-07-clean-prepublicacion.md y seg07-evidence/clean-prepublication.json. Los resultados anteriores de diagnóstico se conservan allí como históricos, no como bloqueos actuales. La ejecución remota del nuevo PR debe comprobarse por separado; los resultados ZAP adjuntos proceden de los escaneos locales originales.
