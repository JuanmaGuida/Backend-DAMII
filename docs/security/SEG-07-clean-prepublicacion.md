# SEG-07: verificación de la rama corregida

Rama feature/seg-07-clean desde origin/master b236690b75bde43ba311f1de79f572b1eac77f5b, actualizado con fetch --prune. El equipo confirmó la limpieza y autorizó publicar. Se copiaron los archivos finales corregidos; no se hizo cherry-pick ni se incluyeron como ancestros los commits 12b397a6f2644a312ff46c2e072b0a9ffbadcd72 y 9c0422a7eb33679d2d96c369fcea879ab86e5964.

## Resultado vigente

15 pruebas SEG-07 aprobadas. Gitleaks 8.30.1 con --self-test: clean, exit 0, cero actuales y cero históricos pendientes; 85 hallazgos históricos corresponden exactamente a las 85 excepciones originales. El inventario y `.gitleaks.toml` son idénticos a master. No se añadieron excepciones.

El clon aislado no es superficial y contiene todas las ramas y tags que descarga actions/checkout@v6 con fetch-depth: 0. El scanner conserva --all --full-history -m. Después de retirar el equipo las ramas antiguas y podar las refs locales desaparecieron los bloqueos. El anterior resultado de ocho pendientes y el ensayo adicional de nueve con el merge del PR viejo se conservan como diagnóstico histórico en clean-prepublication.json; no describen el estado actual.

GitHub todavía anuncia refs/pull/18/head, pero ya no anuncia refs/pull/18/merge en la verificación previa a publicar. El fetch normal de todas las ramas/tags y del merge del nuevo PR no descarga el head del PR anterior. Si se descargaran explícitamente refs antiguas, --all volvería a inspeccionarlas. No se cambió el alcance del scanner para conseguir clean ni se borró el PR anterior.

La corrección de finding_key conserva el digest de 80 bits con prefijo y separadores. Los reportes JSON/HTML, decisiones Medium, conteos y autenticación siguen válidos. Los resultados ZAP corresponden a las ejecuciones originales, no a un nuevo escaneo: baseline 0 High, 2 Medium, 66 Low, 3 Informational; activo cuatro roles completos y cero alertas. No cambian código productivo ni workflows existentes.

Esta evidencia valida el contenido antes de publicar; no declara una ejecución remota de Actions ni un merge sintético aún inexistente. Tras abrir el PR, el CI debe validar su nueva referencia de merge.

```bash
python3 -m unittest discover -s security/seg07 -p 'test_*.py' -v
python3 .github/scripts/check-secrets.py --self-test --output /tmp/seg07-clean-secrets.json
sha256sum -c docs/security/seg07-evidence/SHA256SUMS
```
