#!/usr/bin/env bash
# Streamed over verified SSH; never installed on the server or used to change providers/flags.
set -euo pipefail
[[ $# -eq 4 ]] || exit 64
root="$1"; service="$2"; selection="$3"; expected="$4"
[[ "$root" =~ ^/[A-Za-z0-9._/-]+$ && "$root/" != *'/../'* && "$root/" != *'/./'* ]] || exit 64
[[ "$expected" == M1 || "$expected" == MOCK ]] || exit 64
case "$service" in
  backend) [[ "$selection" =~ ^[0-9a-f]{40}$ ]] || exit 64 ;;
  frontend) [[ "$selection" =~ ^ghcr\.io/santimussi/front-desarrollo-apps-2@sha256:[0-9a-f]{64}$ ]] || exit 64 ;;
  *) exit 64 ;;
esac
for file in compose/.env compose/images.env compose/compose.deploy.yml scripts/validate-integration-config.py scripts/deploy-service.sh; do
  [[ -f "$root/$file" ]] || { echo 'Required deployed Infra file missing.' >&2; exit 66; }
done
compose=(docker compose --project-name compose --project-directory "$root/compose"
  --env-file "$root/compose/.env" --env-file "$root/compose/images.env" -f "$root/compose/compose.deploy.yml")

# Keep private interpolation in memory; neither command prints rendered configuration.
"${compose[@]}" config --format json | python3 "$root/scripts/validate-integration-config.py"
"${compose[@]}" config --format json | python3 -c '
import json, sys
try:
    env = json.load(sys.stdin)["services"]["backend"]["environment"]
    assert env.get("IDENTITY_PROVIDER") == sys.argv[1]
    assert all(str(env.get(key)).lower() == "false" for key in (
        "CORE_OUTBOX_ENABLED", "CORE_INBOX_ENABLED", "CORE_OUTBOX_AUTO_APPROVE_NEW_EVENTS", "SIMULATOR_ENABLED"))
    assert not (env.get("CORE_OUTBOX_APPROVED_EVENT_IDS") or "").strip()
except (AssertionError, KeyError, TypeError, ValueError):
    sys.exit("CD blocked: configured provider differs or Core/simulator is enabled.")
' "$expected"

backend_id="$("${compose[@]}" ps -q backend)"
frontend_id="$("${compose[@]}" ps -q frontend)"
[[ "$backend_id" =~ ^[0-9a-f]{12,64}$ && "$frontend_id" =~ ^[0-9a-f]{12,64}$ ]] || {
  echo 'CD requires both existing application services.' >&2; exit 65;
}
[[ "$(docker inspect --format '{{.State.Running}}' "$backend_id")" == true &&
   "$(docker inspect --format '{{.State.Running}}' "$frontend_id")" == true ]] || exit 65
for pair in "IDENTITY_PROVIDER=$expected" CORE_OUTBOX_ENABLED=false CORE_INBOX_ENABLED=false CORE_OUTBOX_AUTO_APPROVE_NEW_EVENTS=false SIMULATOR_ENABLED=false CORE_OUTBOX_APPROVED_EVENT_IDS=; do
  # Emit only a match marker, never the container environment or rejected values.
  template="{{range .Config.Env}}{{if eq . \"$pair\"}}OK{{end}}{{end}}"
  [[ "$(docker inspect --format "$template" "$backend_id")" == OK ]] || {
    echo 'CD blocked: running Backend provider/flags differ from the expected configuration.' >&2; exit 65;
  }
done
frontend_image="$(docker inspect --format '{{.Config.Image}}' "$frontend_id")"
frontend_provider="$(docker image inspect --format '{{if .Config.Labels}}{{index .Config.Labels "ar.edu.uade.m2.identity-provider"}}{{end}}' "$frontend_image")"
[[ "$frontend_provider" == "$expected" ]] || {
  echo 'CD blocked: running Frontend provider differs; coordinate mode changes manually.' >&2; exit 65;
}

# The installed script retains its shared lock, maintenance marker, TLS preflight,
# Frontend image-label + real-Backend checks and its existing per-service rollback.
exec bash "$root/scripts/deploy-service.sh" "$service" "$selection"
