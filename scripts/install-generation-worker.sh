#!/usr/bin/env bash
set -euo pipefail
app_target="${GAMJAOJ_APP_SSH_TARGET:?Set GAMJAOJ_APP_SSH_TARGET in your private environment}"
cd "$(dirname "$0")/.."
target="${1:?Usage: scripts/install-generation-worker.sh <dedicated-generation-ssh-alias>}"
[[ "$target" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.@-]*$ ]] || { echo 'Invalid SSH target.' >&2; exit 1; }
if [ "$target" = "$app_target" ]; then exec ./scripts/install-generation-container.sh "$target"; fi
[[ "$target" != "${GAMJAOJ_RUNNER_SSH_TARGET:?Set GAMJAOJ_RUNNER_SSH_TARGET}" ]] || { echo 'Use a separate generation account/VM, not the judge or database host.' >&2; exit 1; }
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
chmod 700 "$temporary"
ssh -o BatchMode=yes "$target" bash -s <<'REMOTE'
set -euo pipefail
command -v codex >/dev/null
[ "$(codex --version)" = 'codex-cli 0.160.0' ] || { echo 'Install the reviewed Codex CLI 0.160.0.' >&2; exit 1; }
case " $(id -nG) " in *' docker '*) echo 'Generation account must not have Docker access.' >&2; exit 1;; esac
[ "$(id -u)" != 0 ] || { echo 'Use a non-root generation account.' >&2; exit 1; }
python3 - <<'PY'
import json
from pathlib import Path
path=Path.home()/'gamjaoj-generation/auth/auth.json'
if not path.exists() or json.loads(path.read_text()).get('auth_mode')!='chatgpt':
    raise SystemExit('Log in with ChatGPT using CODEX_HOME=$HOME/gamjaoj-generation/auth codex login --device-auth first.')
PY
[ "$(loginctl show-user "$USER" -p Linger --value)" = yes ] || { echo 'Enable lingering for this generation account.' >&2; exit 1; }
REMOTE
ssh -o BatchMode=yes "$app_target" python3 - <<'PY' > "$temporary/worker.env"
from pathlib import Path
config=dict(line.split('=',1) for line in (Path.home()/'gamjaoj/web/.env').read_text().splitlines() if line and not line.startswith('#') and '=' in line)
print('GAMJAOJ_API_URL=http://'+config['BIND_ADDRESS']+':'+config['HTTP_PORT'])
print('GENERATION_WORKER_TOKEN='+config['GENERATION_WORKER_TOKEN'])
PY
chmod 600 "$temporary/worker.env"
COPYFILE_DISABLE=1 tar --format=ustar --exclude='__pycache__' --exclude='*.pyc' -czf "$temporary/worker.tar.gz" generation deploy/gamjaoj-generation.service
release="$(date -u +%Y%m%dT%H%M%SZ)"
ssh -o BatchMode=yes "$target" 'mkdir -p "$HOME/gamjaoj-generation/releases"; chmod 700 "$HOME/gamjaoj-generation"'
scp -q "$temporary/worker.env" "$target:gamjaoj-generation/worker.env"
scp -q "$temporary/worker.tar.gz" "$target:gamjaoj-generation/releases/$release.tar.gz"
ssh -o BatchMode=yes "$target" bash -s -- "$release" <<'REMOTE'
set -euo pipefail
release="$1"
cd "$HOME/gamjaoj-generation"
chmod 600 worker.env
# Record the resolved CLI path because a user service does not inherit an interactive shell PATH.
cli="$(command -v codex)"
printf 'CODEX_BIN=%s\n' "$cli" >> worker.env
mkdir "releases/$release"
tar -xzf "releases/$release.tar.gz" -C "releases/$release"
rm "releases/$release.tar.gz"
ln -s "releases/$release" "current-$release"
mv -Tf "current-$release" current
mkdir -p "$HOME/.config/systemd/user"
cp current/deploy/gamjaoj-generation.service "$HOME/.config/systemd/user/gamjaoj-generation.service"
systemctl --user daemon-reload
systemctl --user enable --now gamjaoj-generation.service
systemctl --user restart gamjaoj-generation.service
systemctl --user is-active gamjaoj-generation.service
REMOTE
