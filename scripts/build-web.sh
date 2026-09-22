#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
(cd frontend && npm ci --ignore-scripts && npm run build)
docker run --rm --cpus 2 --memory 1g \
  -v "$PWD:/workspace" -v gamjaoj-maven-cache:/root/.m2 -w /workspace/backend \
  maven@sha256:c2a2c58516d160f43b50f12baa427ca86989e0bc942609e04aff61da5d9a7d74 \
  mvn -B clean verify
