#!/usr/bin/env bash
# Isolated real HTTP/session/PostgreSQL/worker/Docker/browser test. No production endpoints.
set -euo pipefail
cd "$(dirname "$0")/.."
export GAMJA_DIAGNOSTIC_E2E="1"
export GAMJAOJ_BASE_URL="http://127.0.0.1:18789"
export GAMJAOJ_API_URL="$GAMJAOJ_BASE_URL"
export INVITE_CODE="diagnostic-local-fixture"
export WORKER_TOKEN="$(openssl rand -hex 32)"
export GAMJA_DIAGNOSTIC_APP="gamja-diagnostic-e2e-app-$$"
export GAMJA_DIAGNOSTIC_DB="gamja-diagnostic-e2e-db-$$"
network="gamja-diagnostic-e2e-$$"
state_dir="$(mktemp -d /tmp/gamja-diagnostic-worker.XXXXXX)"
worker_pid=""
cleanup() {
  if [[ -n "$worker_pid" ]]; then kill "$worker_pid" 2>/dev/null || true; wait "$worker_pid" || true; fi
  docker rm -f "$GAMJA_DIAGNOSTIC_APP" "$GAMJA_DIAGNOSTIC_DB" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  rm -rf "$state_dir"
}
trap cleanup EXIT
[[ -f backend/target/gamjaoj.jar ]] || { echo 'Run scripts/build-web.sh first'; exit 1; }
docker network create "$network" >/dev/null
docker run --rm -d --name "$GAMJA_DIAGNOSTIC_DB" --network "$network" -e POSTGRES_USER=diagnostic_test -e POSTGRES_PASSWORD=diagnostic_test -e POSTGRES_DB=diagnostic_test postgres:17-alpine >/dev/null
docker run --rm -d --name "$GAMJA_DIAGNOSTIC_APP" --network "$network" -p 127.0.0.1:18789:8080 \
  -e DB_URL="jdbc:postgresql://$GAMJA_DIAGNOSTIC_DB:5432/diagnostic_test" -e DB_USER=diagnostic_test -e DB_PASSWORD=diagnostic_test \
  -e INVITE_CODE -e WORKER_TOKEN -e COOKIE_SECURE=false -e SUBMISSIONS_ENABLED=true \
  -v "$PWD/backend/target/gamjaoj.jar:/app/app.jar:ro" \
  eclipse-temurin@sha256:c7d5863b5dd8f26b90c64f1d80cc2b0e5a5e4642f8db9955a370d348edd8f438 \
  java -Xmx256m -jar /app/app.jar >/dev/null
python3 - <<'PY'
import time,urllib.request
for attempt in range(60):
    try:
        urllib.request.urlopen('http://127.0.0.1:18789/api/auth/csrf',timeout=1)
        break
    except Exception:time.sleep(1)
else:raise RuntimeError('Isolated app failed to start')
PY
python3 scripts/stage-diagnostic-bank.py --output "$state_dir/bank.sql"
docker exec -i "$GAMJA_DIAGNOSTIC_DB" psql -U diagnostic_test -d diagnostic_test -v ON_ERROR_STOP=1 < "$state_dir/bank.sql" >/dev/null
# Fixture-only release exercises admission; the checked-in artifact and production stay unreviewed.
docker exec "$GAMJA_DIAGNOSTIC_DB" psql -U diagnostic_test -d diagnostic_test -c "UPDATE diagnostic_bank SET reviewed=true WHERE id='core-a-v1'" >/dev/null
python3 -m runner.worker --state-dir "$state_dir/worker" > /tmp/gamja-diagnostic-e2e-worker.log 2>&1 &
worker_pid=$!
(cd frontend && npx playwright test tests/diagnostic-live.spec.mjs --workers=1 --reporter=line)
