#!/usr/bin/env bash
# Apply a verified ordinary problem release after a private database backup.
# Usage: source .env.ops && scripts/apply-basic-pool.sh <release-id> <artifact.tar.gz> <sql>
set -euo pipefail
[[ $# -eq 3 ]] || { echo 'usage: apply-basic-pool.sh <release-id> <artifact.tar.gz> <sql>' >&2; exit 2; }
: "${GAMJAOJ_APP_SSH_TARGET:?source .env.ops first}"
release=$1
[[ $release =~ ^basic-pool-v1-[a-z0-9-]+$ ]] || { echo 'invalid release identity' >&2; exit 2; }
[[ -f $2 && -f $3 ]] || { echo 'missing private release files' >&2; exit 2; }
dir="gamjaoj/web/problem-releases/$release"
ssh "$GAMJAOJ_APP_SSH_TARGET" "mkdir -p ~/$dir && chmod 700 ~/$dir"
scp -q "$2" "$3" "${GAMJAOJ_APP_SSH_TARGET}:$dir/"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
ssh "$GAMJAOJ_APP_SSH_TARGET" "chmod 600 ~/$dir/* && docker exec gamjaoj-postgres-1 pg_dump -U gamjaoj -d gamjaoj -Fc > ~/$dir/before-$stamp.dump && chmod 600 ~/$dir/before-$stamp.dump"
echo "private backup prepared for $release"
ssh "$GAMJAOJ_APP_SSH_TARGET" 'docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -q -v ON_ERROR_STOP=1' < "$3"
echo "ordinary problem release applied: $release"
