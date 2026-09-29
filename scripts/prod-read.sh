#!/usr/bin/env bash
# Read-only production database query for diagnostics.
#   ./scripts/prod-read.sh "SELECT status, count(*) FROM judge_job GROUP BY status"
# Every query runs inside BEGIN READ ONLY with a 20 s statement timeout and is rolled back, so PostgreSQL itself
# rejects writes. Transaction control, session settings, psql meta-commands (\! runs a shell), COPY and
# server-side file/process functions are refused before anything is sent. Output is capped at 400 lines.
set -euo pipefail
cd "$(dirname "$0")/.."
[[ $# -eq 1 && -n "${1//[[:space:]]/}" ]] || { echo 'usage: ./scripts/prod-read.sh "SELECT ..."' >&2; exit 2; }
[[ -f .env.ops ]] && source .env.ops
: "${GAMJAOJ_APP_SSH_TARGET:?Set GAMJAOJ_APP_SSH_TARGET in .env.ops}"
sql="$1"
if [[ "$sql" == *\\* ]]; then echo 'psql meta-commands are not allowed.' >&2; exit 2; fi
if ! grep -qiE '^[[:space:]]*(select|with|explain[[:space:]]+select|table)[[:space:]]' <<<"$sql"; then
  echo 'Only read queries (SELECT, WITH, EXPLAIN SELECT, TABLE) are allowed.' >&2; exit 2
fi
if grep -qiE '(^|;)\s*(commit|rollback|abort|begin|start|end|savepoint|release|set|reset|copy|do|call|lock)\b' <<<"$sql" \
   || grep -qiE '(pg_read|pg_ls_dir|pg_stat_file|pg_terminate|pg_cancel|pg_reload|pg_rotate|lo_(import|export)|dblink|pg_sleep|set_config)' <<<"$sql"; then
  echo 'Statement refused: transaction control, settings, COPY and server-side file or process functions are not allowed.' >&2; exit 2
fi
printf "BEGIN READ ONLY;\nSET LOCAL statement_timeout = '20s';\n%s;\nROLLBACK;\n" "$sql" \
  | ssh -o BatchMode=yes -o ConnectTimeout=15 "$GAMJAOJ_APP_SSH_TARGET" \
      'docker exec -i gamjaoj-postgres-1 psql -U gamjaoj -d gamjaoj -X -q -v ON_ERROR_STOP=1 -P pager=off' \
  | head -n 400
