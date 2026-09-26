#!/usr/bin/env bash
set -euo pipefail
app_target="${GAMJAOJ_APP_SSH_TARGET:?Set GAMJAOJ_APP_SSH_TARGET in your private environment}"
cd "$(dirname "$0")/.."
target="${1:?Usage: scripts/install-worker.sh <separate-runner-ssh-alias>}"
[[ "$target" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.@-]*$ ]] || { echo 'Invalid SSH target.' >&2; exit 1; }
[ "$target" != "$app_target" ] || { echo 'Use a separate Runner VM; shared-server checks use the smoke script.' >&2; exit 1; }
temporary="$(mktemp -d)"
trap 'rm -rf "$temporary"' EXIT
ssh -o BatchMode=yes "$app_target" python3 - <<'PY' > "$temporary/worker.env"
from pathlib import Path
config = dict(line.split('=',1) for line in (Path.home()/'gamjaoj/web/.env').read_text().splitlines() if line and not line.startswith('#'))
print('GAMJAOJ_API_URL=http://'+config['BIND_ADDRESS']+':'+config['HTTP_PORT'])
print('WORKER_TOKEN='+config['WORKER_TOKEN'])
PY
chmod 600 "$temporary/worker.env"
COPYFILE_DISABLE=1 tar --format=ustar --exclude='__pycache__' --exclude='*.pyc' -czf "$temporary/worker.tar.gz" runner deploy/gamjaoj-worker.service
release="$(date -u +%Y%m%dT%H%M%SZ)"
ssh -o BatchMode=yes "$target" 'mkdir -p "$HOME/gamjaoj-worker/releases"; chmod 700 "$HOME/gamjaoj-worker"'
scp -q "$temporary/worker.env" "$target:gamjaoj-worker/worker.env"
scp -q "$temporary/worker.tar.gz" "$target:gamjaoj-worker/releases/$release.tar.gz"
ssh -o BatchMode=yes "$target" bash -s -- "$release" <<'REMOTE'
set -euo pipefail
release="$1"
cd "$HOME/gamjaoj-worker"
chmod 600 worker.env
command -v python3 >/dev/null
docker info >/dev/null
[ "$(loginctl show-user "$USER" -p Linger --value)" = yes ] || {
  echo 'Enable lingering for the dedicated worker account before installing its persistent user service.' >&2
  exit 1
}
systemd-run --user --wait --pipe --quiet docker info >/dev/null || {
  echo 'The systemd user manager cannot access Docker. After changing Docker group membership, restart that manager or reboot the dedicated VM.' >&2
  exit 1
}
mkdir "releases/$release"
tar -xzf "releases/$release.tar.gz" -C "releases/$release"
rm "releases/$release.tar.gz"
docker pull "$(cat "releases/$release/runner/java-image.txt")"
docker pull "$(cat "releases/$release/runner/java21-image.txt")"
docker pull "$(cat "releases/$release/runner/cpp-image.txt")"
docker pull "$(cat "releases/$release/runner/python-image.txt")"
systemd-run --user --wait --pipe --quiet --working-directory="$PWD/releases/$release" \
  python3 -c 'from runner.judge import engine_control; engine_control()' || {
  echo 'Local Docker control API check failed; current worker release is unchanged.' >&2
  exit 1
}
ln -s "releases/$release" "current-$release"
mv -Tf "current-$release" current
mkdir -p "$HOME/.config/systemd/user"
cp current/deploy/gamjaoj-worker.service "$HOME/.config/systemd/user/gamjaoj-worker.service"
systemctl --user daemon-reload
systemctl --user enable gamjaoj-worker.service
systemctl --user restart gamjaoj-worker.service
systemctl --user is-active gamjaoj-worker.service
REMOTE
