#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "$(git branch --show-current)" != main ]; then
  echo 'M0 deployment must originate from main.' >&2
  exit 1
fi

release="$(date -u +%Y%m%dT%H%M%SZ)"
archive="$(mktemp)"
trap 'rm -f "$archive"' EXIT
# Explicit allowlist; docs, credentials, local reports, and Git data are never transferred.
COPYFILE_DISABLE=1 tar --format=ustar --exclude='__pycache__' --exclude='*.pyc' -czf "$archive" \
  runner tests problems examples README.md
ssh -o BatchMode=yes -o ConnectTimeout=10 ocr-serv 'mkdir -p "$HOME/gamjaoj/releases"'
scp -q "$archive" "ocr-serv:gamjaoj/releases/$release.tar.gz"
ssh -o BatchMode=yes ocr-serv bash -s -- "$release" <<'REMOTE'
set -euo pipefail
release="$1"
cd "$HOME/gamjaoj"
mkdir -p "releases/$release"
tar -xzf "releases/$release.tar.gz" -C "releases/$release"
rm "releases/$release.tar.gz"
cd "releases/$release"
mkdir -p .state
docker ps -q | sort > .state/existing-containers.txt
if docker network inspect gamjaoj_backend >/dev/null 2>&1; then
  owner="$(docker network inspect gamjaoj_backend --format '{{index .Labels "com.gamjaoj.owner"}}')"
  internal="$(docker network inspect gamjaoj_backend --format '{{.Internal}}')"
  [ "$owner" = gamjaoj ] && [ "$internal" = true ] || {
    echo 'Existing network is not the expected GamjaOJ internal network.' >&2
    exit 1
  }
else
  docker network create --internal --label com.gamjaoj.owner=gamjaoj gamjaoj_backend
fi
image="$(cat runner/java-image.txt)"
docker image inspect "$image" >/dev/null 2>&1 || docker pull "$image"
GAMJAOJ_DOCKER_TESTS=1 python3 -m unittest -v tests.test_judge 2>&1 | tee .state/tests.log
python3 -m runner.judge examples/Main.java > .state/smoke.json
docker ps -aq --filter label=com.gamjaoj.role=sandbox > .state/remaining-sandboxes.txt
[ ! -s .state/remaining-sandboxes.txt ]
while IFS= read -r container; do
  [ "$(docker inspect --format '{{.State.Running}}' "$container")" = true ]
done < .state/existing-containers.txt
cd ../..
ln -s "releases/$release" "current-$release"
mv -Tf "current-$release" current
echo "M0 verified and installed at ~/gamjaoj/current (release $release). No public port opened."
REMOTE
