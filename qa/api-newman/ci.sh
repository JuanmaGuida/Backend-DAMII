#!/usr/bin/env bash
set -euo pipefail
started=$SECONDS

jar="${1:?Executable jar required}"
postgres_container="${2:?Job-local PostgreSQL service required}"
report="${3:?Report directory required}"
export SERVER_ADDRESS=127.0.0.1 SERVER_PORT="${SERVER_PORT:-18080}"
export SPRING_PROFILES_ACTIVE=dev,qa-isolated SPRING_CONFIG_LOCATION=classpath:/
export SPRING_JPA_SHOW_SQL=false ATTACHMENT_STORAGE_ROOT="$report/attachments"
export IDENTITY_PROVIDER=MOCK AI_ENABLED=false SIMULATOR_ENABLED=false
export CORE_OUTBOX_ENABLED=false CORE_INBOX_ENABLED=false CORE_OUTBOX_LOCAL_QA=false
export CORE_OUTBOX_AUTO_APPROVE_NEW_EVENTS=false CORE_OUTBOX_APPROVED_EVENT_IDS=
for role in CITIZEN AGENT AREA_RESPONSIBLE ADMIN; do
  fixture_value="$(node -e "process.stdout.write(require('node:crypto').randomBytes(16).toString('hex'))")"
  export "MOCK_${role}_PASSWORD=$fixture_value"
done
TRACKING_CODE_ENCRYPTION_KEY_BASE64="$(node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('base64'))")"
export TRACKING_CODE_ENCRYPTION_KEY_BASE64 TRACKING_CODE_ENCRYPTION_KEY_ID=api-qa-pilot

mkdir -p "$report"
git rev-parse HEAD > "$report/revision.txt"
sha256sum < "$jar" | cut -d ' ' -f 1 > "$report/jar-sha256.txt"
cp qa/api-newman/coverage.md "$report/coverage.md"
java -Duser.timezone=UTC -jar "$jar" > "$RUNNER_TEMP/newman-mock-backend.log" 2>&1 &
backend_pid=$!
trap 'kill "$backend_pid" 2>/dev/null || true; wait "$backend_pid" 2>/dev/null || true' EXIT

node qa/api-newman/ready.mjs "http://127.0.0.1:$SERVER_PORT" | tee "$report/readiness.json"
read_counts() {
  docker exec -i "$postgres_container" psql -U m2_newman_qa -d m2_newman_qa -v ON_ERROR_STOP=1 -At < qa/api-newman/db-row-counts.sql
}
docker exec -i "$postgres_container" psql -U m2_newman_qa -d m2_newman_qa -v ON_ERROR_STOP=1 < qa/api-newman/seed-conflict.sql
read_counts > "$report/counts-before.json"
newman_status=0
node qa/api-newman/run-newman.cjs "http://127.0.0.1:$SERVER_PORT" "$report" || newman_status=$?
read_counts > "$report/counts-after.json"
effects_status=0
node qa/api-newman/check-effects.mjs "$report/counts-before.json" "$report/counts-after.json" "$report/effects.json" || effects_status=$?
kill "$backend_pid" 2>/dev/null || true
wait "$backend_pid" 2>/dev/null || true
trap - EXIT

m1_status=0
bash qa/api-newman/run-m1.sh "$jar" "$report" || m1_status=$?
printf '%s\n' "$((SECONDS - started))" > "$report/suite-wall-seconds.txt"
node qa/api-newman/merge-execution.cjs "$report"
test "$newman_status" -eq 0 && test "$effects_status" -eq 0 && test "$m1_status" -eq 0
