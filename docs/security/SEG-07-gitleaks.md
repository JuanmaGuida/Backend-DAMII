# SEG-07: identificadores y Gitleaks 8.30.1

`finding_key` no es una credencial: se obtiene de SHA-256 sobre `pluginId`, `alertRef`, `url`, `param`, `method` y `scan`, unidos con `|`, y toma los primeros 80 bits. No incorpora contraseñas, tokens ni aleatoriedad de autenticación. El detector generic-api-key interpreta como clave el digest hexadecimal continuo en una asignación cuyo nombre contiene key.

La corrección conserva el digest con formato público `zap:hhhh:hhhh:hhhh:hhhh:hhhh`. Los separadores evitan cadenas continuas con apariencia de clave sin cambiar reglas del detector. Identificadores, tratamientos, documentación y reportes JSON/HTML se migran juntos. Se conserva la diferenciación por variante de regla y no cambian alertas, severidades, decisiones, cobertura ni evidencia de autenticación. No es un nuevo escaneo ZAP.

## Tratamiento histórico sin excepciones nuevas

La investigación anterior reprodujo dos falsos positivos del identificador derivado de 10055-5 en los commits 12b397a6f2644a312ff46c2e072b0a9ffbadcd72 y 9c0422a7eb33679d2d96c369fcea879ab86e5964. Sus metadatos se conservan en gitleaks-review.json y en la auditoría previa de clean-prepublication.json. No se activó ninguna de las propuestas de excepción.

La solución final parte de master y copia la implementación corregida sin esos commits como ancestros. Tras la limpieza de ramas realizada por el equipo y fetch --prune, Gitleaks no encuentra hallazgos pendientes en las refs que descarga el CI. Se mantienen exactamente las 85 excepciones originales, sin modificar `.gitleaks.toml`, sin ignorar categorías ni excluir refs en el scanner.

GitHub todavía anuncia refs/pull/18/head. No se descarga como parte del checkout del PR nuevo. Un fetch explícito de referencias de PR anteriores puede volver a introducir el historial viejo en --all; la auditoría previa demuestra esa diferencia. No se cerró ni modificó el PR anterior por esta publicación.

## Verificación reproducible

```bash
git fetch --prune origin
python3 -m unittest discover -s security/seg07 -p 'test_*.py' -v
python3 .github/scripts/check-secrets.py --self-test --output /tmp/seg07-secrets-summary.json
sha256sum -c docs/security/seg07-evidence/SHA256SUMS
```

Resultado vigente: 15 pruebas aprobadas y detector 8.30.1 con self-test aprobado, clean, exit 0, cero actuales, cero históricos pendientes y 85/85 excepciones originales coincidentes. El binario oficial y su checksum están fijados por check-secrets.py. Los reportes de Gitleaks sólo contienen metadatos; no se publica salida cruda del detector.
