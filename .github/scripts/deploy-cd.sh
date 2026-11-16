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
    assert str(env.get("SIMULATOR_ENABLED")).lower() == "false"
except (AssertionError, KeyError, TypeError, ValueError):
    sys.exit("CD blocked: configured provider differs or simulator is enabled.")
' "$expected"

backend_id="$("${compose[@]}" ps -q backend)"
frontend_id="$("${compose[@]}" ps -q frontend)"
[[ "$backend_id" =~ ^[0-9a-f]{12,64}$ && "$frontend_id" =~ ^[0-9a-f]{12,64}$ ]] || {
  echo 'CD requires both existing application services.' >&2; exit 65;
}
# Inspect privately: Frontend does not restart Backend or depend on its Core flags.
# Backend must not silently apply staged Core configuration during a routine image rollout.
"${compose[@]}" config --format json | python3 -c '
import json, shutil, subprocess, sys
try:
    config = json.load(sys.stdin)
    configured = config["services"]["backend"]["environment"]
    containers = json.loads(subprocess.run(
        [shutil.which("docker"), "inspect", sys.argv[1], sys.argv[2]],
        capture_output=True, check=True).stdout)
    assert len(containers) == 2 and all(c["State"]["Running"] for c in containers)
    actual = dict(pair.split("=", 1) for pair in containers[0]["Config"]["Env"])
    assert actual.get("IDENTITY_PROVIDER") == sys.argv[3]
    assert actual.get("SIMULATOR_ENABLED") == "false"
    # Reuse Infra policy for the live mode too; capture output to keep secrets private.
    config["services"]["backend"]["environment"] = actual
    subprocess.run([sys.executable, sys.argv[5]], input=json.dumps(config).encode(),
                   capture_output=True, check=True)
    if sys.argv[4] == "backend":
        keys = {key for env in (configured, actual) for key in env if key.startswith("CORE_")}
        assert all(str(configured.get(key)) == actual.get(key) for key in keys)
except (AssertionError, KeyError, TypeError, ValueError, OSError, subprocess.SubprocessError):
    sys.exit("CD blocked: live services/provider differ or Backend Core configuration has drifted.")
' "$backend_id" "$frontend_id" "$expected" "$service" "$root/scripts/validate-integration-config.py"
frontend_image="$(docker inspect --format '{{.Config.Image}}' "$frontend_id")"
frontend_provider="$(docker image inspect --format '{{if .Config.Labels}}{{index .Config.Labels "ar.edu.uade.m2.identity-provider"}}{{end}}' "$frontend_image")"
[[ "$frontend_provider" == "$expected" ]] || {
  echo 'CD blocked: running Frontend provider differs; coordinate mode changes manually.' >&2; exit 65;
}

# The installed script retains its shared lock, maintenance marker, TLS preflight,
# Frontend image-label + real-Backend checks and its existing per-service rollback.
exec bash "$root/scripts/deploy-service.sh" "$service" "$selection"
