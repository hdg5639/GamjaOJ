#!/usr/bin/env bash
set -euo pipefail
app_target="${GAMJAOJ_APP_SSH_TARGET:?Set GAMJAOJ_APP_SSH_TARGET in your private environment}"
cd "$(dirname "$0")/.."
export GAMJAOJ_E2E_USERNAME="browser_$(openssl rand -hex 5)"
export GAMJAOJ_BASE_URL="${GAMJAOJ_BASE_URL:-$(ssh -o BatchMode=yes "$app_target" 'sed -n "s/^PUBLIC_BASE_URL=//p" ~/gamjaoj/web/.env')}"
cleanup() {
  ssh -o BatchMode=yes "$app_target" bash -s -- "$GAMJAOJ_E2E_USERNAME" <<'REMOTE'
set -euo pipefail
docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -v ON_ERROR_STOP=1 -v test_username="$1" <<'SQL'
DELETE FROM spring_session WHERE principal_name = :'test_username';
DELETE FROM app_user WHERE username = :'test_username';
SQL
REMOTE
}
trap cleanup EXIT
mkdir -p .state
cd frontend
test_files=(tests/auth.spec.mjs)
if [ "${GAMJAOJ_BROWSER_AUTH_ONLY:-0}" != 1 ]; then test_files+=(tests/drafts.spec.mjs); fi
npx playwright test "${test_files[@]}" --workers=1 --reporter=line
