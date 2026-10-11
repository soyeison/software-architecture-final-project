#!/usr/bin/env bash
# Valida los contratos OpenAPI con Redocly CLI (ejecutado en Docker).
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
REDOCLY_IMAGE="redocly/cli:2.62.1"
FILES=(
  "inventory-service.yaml"
  "catalog-service.yaml"
)

status=0
for file in "${FILES[@]}"; do
  if [[ ! -f "$ROOT/docs/contracts/openapi/$file" ]]; then
    echo "FAIL: falta docs/contracts/openapi/$file"
    status=1
    continue
  fi
  if docker run --rm -v "$ROOT/docs/contracts:/spec:z" "$REDOCLY_IMAGE" \
      lint "/spec/openapi/$file" --config /spec/redocly.yaml --format=stylish; then
    echo "PASS: $file"
  else
    echo "FAIL: $file"
    status=1
  fi
done
exit $status
