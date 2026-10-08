#!/usr/bin/env bash
set -euo pipefail

# Gitleaks v8.30.1, pinned multi-platform image.
scanner_image='ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f'
repository_path="$(git -C "${1:-.}" rev-parse --show-toplevel)"

docker run --rm --network none \
  --mount "type=bind,source=${repository_path},target=/repo,readonly" \
  --env GIT_CONFIG_COUNT=1 \
  --env GIT_CONFIG_KEY_0=safe.directory \
  --env GIT_CONFIG_VALUE_0=/repo \
  "$scanner_image" git /repo \
  --log-opts='--all' --redact=100 --no-banner --no-color \
  --ignore-gitleaks-allow --timeout=480
