#!/bin/sh
set -eu
CONTROL_OJ_HOST="$(printf '%s' "${CONTROL_OJ_HOST:-}" | tr '[:upper:]' '[:lower:]')"
case "$CONTROL_OJ_HOST" in *[!a-z0-9.-]*) echo 'CONTROL_OJ_HOST must contain only a hostname' >&2; exit 1;; esac
export CONTROL_OJ_HOST
envsubst '${CONTROL_OJ_HOST}' < /etc/nginx/nginx.conf.template > /tmp/nginx.conf
exec nginx -c /tmp/nginx.conf -g 'daemon off;'
