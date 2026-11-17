#!/usr/bin/env bash
set -Eeuo pipefail
# No xtrace: environment contains freshly generated disposable secrets.
cd "$(dirname "$0")/../.."
mode="${1:-}"
[[ "$mode" == baseline || "$mode" == active ]] || { echo 'Usage: bash security/seg07/run.sh baseline|active'; exit 2; }
export SEG07_MODE="$mode"
export SEG07_REPORT_DIR="$(pwd)/security/seg07/reports/$mode"
mkdir -p "$SEG07_REPORT_DIR"
# Each invocation owns a separate project and database; no .env file is loaded.
export COMPOSE_PROJECT_NAME="seg07-${mode}-$(python3 -c 'import secrets; print(secrets.token_hex(5))')"
while IFS= read -r assignment; do export "$assignment"; done < <(python3 - <<'PY'
import secrets, base64
for name in ['DB', 'CITIZEN', 'AGENT', 'AREA_RESPONSIBLE', 'ADMIN']:
    print('SEG07_' + name + '_PASSWORD=' + secrets.token_urlsafe(32))
print('SEG07_SETUP_KEY=' + secrets.token_urlsafe(32))
print('SEG07_KEY=' + base64.b64encode(secrets.token_bytes(32)).decode())
PY
)
compose=(docker compose --env-file /dev/null -f security/seg07/compose.yml)
cleanup() { "${compose[@]}" down --volumes --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT
python3 - "$SEG07_REPORT_DIR" <<'PY'
import json, sys, pathlib
p = pathlib.Path(sys.argv[1])
for f in p.glob('*'):
    if f.is_file(): f.unlink()
(p / 'execution.json').write_text(json.dumps({'status': 'INCOMPLETE', 'reason': 'Scanner has not completed'}))
PY
if [[ -n "${CODEX_PROXY_CERT:-}" ]]; then
  settings_file="$(mktemp)"
  python3 - "$settings_file" <<'PYSET'
import os, sys, urllib.parse, xml.etree.ElementTree as E
root = E.Element('settings')
url = urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY', os.environ.get('https_proxy', '')))
if url.hostname:
    p = E.SubElement(E.SubElement(root, 'proxies'), 'proxy')
    for k, v in {'id': 'seg07-session', 'active': 'true', 'protocol': 'http', 'host': url.hostname, 'port': str(url.port or 80)}.items():
        E.SubElement(p, k).text = v
E.ElementTree(root).write(sys.argv[1])
PYSET
  proxy_host_args=()
  if getent ahostsv4 proxy >/dev/null; then
    proxy_ip="$(getent ahostsv4 proxy | head -1 | awk '{print $1}')"
    proxy_host_args=(--add-host "proxy:$proxy_ip")
  fi
  docker build "${proxy_host_args[@]}" -f security/seg07/Dockerfile --secret "id=proxy_ca,src=$CODEX_PROXY_CERT" --secret "id=maven_settings,src=$settings_file" -t seg07-backend:local .
  rm -f "$settings_file"
else
  "${compose[@]}" build application
fi
"${compose[@]}" up -d db application backend
"${compose[@]}" run --rm scanner
