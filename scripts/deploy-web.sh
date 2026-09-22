#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
[ "$(git branch --show-current)" = main ] || { echo 'Deploy from main.' >&2; exit 1; }
./scripts/build-web.sh
release="$(date -u +%Y%m%dT%H%M%SZ)"
archive="$(mktemp)"
trap 'rm -f "$archive"' EXIT
COPYFILE_DISABLE=1 tar --format=ustar --exclude='__pycache__' --exclude='*.pyc' -czf "$archive" \
  backend/target/gamjaoj.jar deploy runner problems examples tests scripts/smoke-auth.py scripts/smoke-submissions.py
ssh -o BatchMode=yes ocr-serv 'mkdir -p "$HOME/gamjaoj/web/releases"'
scp -q "$archive" "ocr-serv:gamjaoj/web/releases/$release.tar.gz"
ssh -o BatchMode=yes ocr-serv bash -s -- "$release" <<'REMOTE'
set -euo pipefail
umask 077
release="$1"
cd "$HOME/gamjaoj/web"
exec 9>.deploy.lock
flock -n 9 || { echo 'Another GamjaOJ deployment is running.' >&2; exit 1; }
mkdir "releases/$release"
tar -xzf "releases/$release.tar.gz" -C "releases/$release"
rm "releases/$release.tar.gz"
if [ ! -f .env ]; then
  python3 - <<'PY'
import pathlib, secrets
pathlib.Path('.env').write_text(
    'DB_PASSWORD=' + secrets.token_urlsafe(32) + '\n'
    'INVITE_CODE=' + secrets.token_urlsafe(18) + '\n'
    'BIND_ADDRESS=192.0.2.10\nHTTP_PORT=18081\nCOOKIE_SECURE=false\n')
PY
fi
chmod 600 .env
python3 - <<'PY'
from pathlib import Path
import secrets
path = Path('.env')
config = path.read_text()
if not any(line.startswith('WORKER_TOKEN=') for line in config.splitlines()):
    config += '\nWORKER_TOKEN=' + secrets.token_urlsafe(32) + '\n'
if not any(line.startswith('SUBMISSIONS_ENABLED=') for line in config.splitlines()):
    config += 'SUBMISSIONS_ENABLED=false\n'
path.write_text(config)
PY
owner="$(docker network inspect gamjaoj_backend --format '{{index .Labels "com.gamjaoj.owner"}}')"
[ "$owner" = gamjaoj ] || { echo 'Dedicated network ownership mismatch.' >&2; exit 1; }
docker ps --format '{{.ID}} {{.Label "com.docker.compose.project"}}' \
  | awk '$2 != "gamjaoj" {print $1}' | sort > "releases/$release/existing-containers.txt"
export GAMJAOJ_IMAGE="gamjaoj-web:$release"
docker build --network none -f "releases/$release/deploy/Dockerfile" -t "$GAMJAOJ_IMAGE" "releases/$release"
docker compose --env-file .env -f "releases/$release/deploy/compose.yaml" up -d --wait --wait-timeout 180
python3 "releases/$release/scripts/smoke-auth.py" --ipv4 --env-file .env --compose "releases/$release/deploy/compose.yaml" </dev/null
if python3 - <<'PY'
from pathlib import Path
config = dict(line.split('=',1) for line in Path('.env').read_text().splitlines() if line and not line.startswith('#'))
raise SystemExit(0 if config.get('SUBMISSIONS_ENABLED','false').lower() == 'true' else 1)
PY
then
  echo 'General submissions enabled: verify execution on the dedicated Runner VM; shared-VM execution smoke skipped.'
else
  python3 -u "releases/$release/scripts/smoke-submissions.py" --env-file .env --compose "releases/$release/deploy/compose.yaml" </dev/null
fi
while IFS= read -r container; do
  [ "$(docker inspect --format '{{.State.Running}}' "$container")" = true ]
done < "releases/$release/existing-containers.txt"
printf '%s\n' "$GAMJAOJ_IMAGE" > "releases/$release/image.txt"
ln -s "releases/$release" "current-$release"
mv -Tf "current-$release" current
echo "GamjaOJ web verified at http://192.0.2.10:18081 (release $release)."
REMOTE
