#!/usr/bin/env bash
# Apply prepared diagnostic bank SQL on the app host, in order, after a pg_dump backup.
# Usage: source .env.ops && ./scripts/apply-diagnostic-release.sh <bank-id> <artifact.json> <review.json> <sql>...
# Files are kept (mode 600) in ~/gamjaoj/web/bank-releases/<bank-id>/ next to the backup. Stops at the first error.
set -euo pipefail
[[ $# -ge 4 ]] || { echo "usage: $0 <bank-id> <artifact.json> <review.json> <sql>..." >&2; exit 2; }
: "${GAMJAOJ_APP_SSH_TARGET:?source .env.ops first}"
bank=$1; shift
[[ $bank =~ ^[a-z0-9-]+$ ]] || { echo "invalid bank id" >&2; exit 2; }
dir="gamjaoj/web/bank-releases/$bank"
ssh "$GAMJAOJ_APP_SSH_TARGET" "mkdir -p ~/$dir && chmod 700 ~/$dir"
scp -q "$@" "$GAMJAOJ_APP_SSH_TARGET:$dir/"
ssh "$GAMJAOJ_APP_SSH_TARGET" "chmod 600 ~/$dir/*"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
ssh "$GAMJAOJ_APP_SSH_TARGET" "docker exec gamjaoj-postgres-1 pg_dump -U gamjaoj -d gamjaoj -Fc > ~/$dir/before-$stamp.dump && chmod 600 ~/$dir/before-$stamp.dump"
echo "backup: ~/$dir/before-$stamp.dump"
shift 2
for sql in "$@"; do
  echo "applying $(basename "$sql")"
  ssh "$GAMJAOJ_APP_SSH_TARGET" "docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -q -v ON_ERROR_STOP=1" < "$sql"
done
ssh "$GAMJAOJ_APP_SSH_TARGET" "docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -At -c \"SELECT id, reviewed FROM diagnostic_bank ORDER BY id\""
