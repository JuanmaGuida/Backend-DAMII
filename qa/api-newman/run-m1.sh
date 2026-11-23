#!/usr/bin/env bash
set -euo pipefail

jar="$1"
report="$2"
scratch="$(mktemp -d "$RUNNER_TEMP/m1-newman.XXXXXX")"
stub_pid=''
backend_pid=''
cleanup() {
  for pid in "$backend_pid" "$stub_pid"; do
    if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  done
  rm -f -- "$scratch/key.pem" "$scratch/cert.pem" "$scratch/trust.p12" "$scratch/port"
  rmdir -- "$scratch"
}
trap cleanup EXIT
export IDENTITY_PROVIDER=M1 M2_AUTH_BROWSER_ORIGIN=https://m2.example.test
export SERVER_ADDRESS=127.0.0.1 SERVER_PORT="${SERVER_PORT:-18080}"
for fixture in APP_JWT_SECRET M1_QA_FIXTURE_CREDENTIAL; do
  fixture_value="$(node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('hex'))")"
  if [[ "$fixture" == APP_JWT_SECRET ]]; then fixture_value="$(node -e "process.stdout.write(require('node:crypto').randomBytes(32).toString('base64'))")"; fi
  export "$fixture=$fixture_value"
done
trust_phrase="$(node -e "process.stdout.write(require('node:crypto').randomBytes(16).toString('hex'))")"
MSYS2_ARG_CONV_EXCL=/CN= openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj '/CN=127.0.0.1' \
  -addext 'subjectAltName=IP:127.0.0.1' -keyout "$scratch/key.pem" -out "$scratch/cert.pem" >/dev/null 2>&1
keytool -importcert -noprompt -alias m1-newman-fixture -file "$scratch/cert.pem" \
  -keystore "$scratch/trust.p12" -storetype PKCS12 -storepass "$trust_phrase" >/dev/null 2>&1
node qa/api-newman/m1-stub.cjs "$scratch" &
stub_pid=$!
for ((attempt=0; attempt<50; attempt++)); do
  [[ -s "$scratch/port" ]] && break
  kill -0 "$stub_pid"
  sleep 0.1
done
export M1_BASE_URL="https://127.0.0.1:$(cat "$scratch/port")"
java -Duser.timezone=UTC "-Djavax.net.ssl.trustStore=$scratch/trust.p12" "-Djavax.net.ssl.trustStorePassword=$trust_phrase" \
  -jar "$jar" > "$RUNNER_TEMP/newman-m1-backend.log" 2>&1 &
backend_pid=$!
node qa/api-newman/ready.mjs "http://127.0.0.1:$SERVER_PORT" | tee "$report/readiness-m1.json"
node qa/api-newman/run-newman.cjs "http://127.0.0.1:$SERVER_PORT" "$report" m1
