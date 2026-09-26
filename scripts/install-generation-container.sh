#!/usr/bin/env bash
# Shared app VM: only dedicated generator container/resources are created or restarted.
set -euo pipefail
app_target="${GAMJAOJ_APP_SSH_TARGET:?Set GAMJAOJ_APP_SSH_TARGET in your private environment}"
cd "$(dirname "$0")/.."
target="${1:-$app_target}"
[[ "$target" = "$app_target" ]] || { echo 'Use the configured application endpoint.' >&2; exit 1; }
archive="$(mktemp)"
trap 'rm -f "$archive"' EXIT
COPYFILE_DISABLE=1 tar --format=ustar --exclude='__pycache__' --exclude='*.pyc' -czf "$archive" generation deploy/generation.Dockerfile deploy/generation-compose.yaml
release="$(date -u +%Y%m%dT%H%M%SZ)"
ssh -o BatchMode=yes "$target" 'mkdir -p "$HOME/gamjaoj-generation/releases"; chmod 700 "$HOME/gamjaoj-generation"'
scp -q "$archive" "$target:gamjaoj-generation/releases/$release.tar.gz"
ssh -o BatchMode=yes "$target" bash -s -- "$release" <<'REMOTE'
set -euo pipefail
umask 077
release="$1"
cd "$HOME/gamjaoj-generation"
exec 9>.install.lock
flock -n 9
if docker network inspect gamjaoj_generation >/dev/null 2>&1; then
  owner="$(docker network inspect gamjaoj_generation --format '{{index .Labels "com.gamjaoj.owner"}}')"
  [[ "$owner" = gamjaoj-generation ]] || { echo 'Generation network ownership mismatch.' >&2; exit 1; }
fi
cli="$(readlink -f "$HOME/.local/bin/codex")"
version="$("$cli" --version)"
[[ "$version" = 'codex-cli 0.155.1' || "$version" = 'codex-cli 0.154.0' ]] || { echo 'Unreviewed Codex CLI version.' >&2; exit 1; }
mkdir "releases/$release"
tar -xzf "releases/$release.tar.gz" -C "releases/$release"
rm "releases/$release.tar.gz"
cp "$cli" "releases/$release/codex"
chmod 755 "releases/$release/codex"
python3 - <<'PY'
from pathlib import Path
import json
config=dict(line.split('=',1) for line in (Path.home()/'gamjaoj/web/.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
Path('worker.env').write_text('GAMJAOJ_API_URL=http://'+config['BIND_ADDRESS']+':'+config['HTTP_PORT']+'\nGENERATION_WORKER_TOKEN='+config['GENERATION_WORKER_TOKEN']+'\n')
auth=Path.home()/'.codex/auth.json'
if json.loads(auth.read_text()).get('auth_mode')!='chatgpt':raise SystemExit('ChatGPT authentication required')
PY
sudo mkdir -p auth state
sudo chown 65532:65532 auth state
sudo chmod 700 auth state
if ! sudo test -f auth/auth.json; then
  sudo cp "$HOME/.codex/auth.json" auth/auth.json
  sudo chown 65532:65532 auth/auth.json
  sudo chmod 600 auth/auth.json
fi
chmod 600 worker.env
# The backend network and Docker socket are not attached to the worker.
export GENERATION_IMAGE="gamjaoj-generation:$release"
docker build --network none -f "releases/$release/deploy/generation.Dockerfile" -t "$GENERATION_IMAGE" "releases/$release"
cp "releases/$release/deploy/generation-compose.yaml" compose.yaml
python3 - "$GENERATION_IMAGE" <<'PYENV'
from pathlib import Path
import sys
path=Path('.env')
old=path.read_text().splitlines() if path.exists() else []
kept=[line for line in old if not line.startswith('GENERATION_IMAGE=')]
path.write_text('\n'.join(kept+['GENERATION_IMAGE='+sys.argv[1]])+'\n')
PYENV
docker compose -f compose.yaml run --rm --no-deps --entrypoint /usr/local/bin/codex worker --version </dev/null
docker compose -f compose.yaml up -d
ln -s "releases/$release" "current-$release"
mv -Tf "current-$release" current
docker compose -f compose.yaml ps
REMOTE
