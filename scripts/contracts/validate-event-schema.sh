#!/usr/bin/env bash
# Valida el JSON Schema del evento InventoryStockChanged y sus ejemplos (check-jsonschema en Docker).
# - El schema debe cumplir el metaschema draft 2020-12.
# - examples/valid.json debe validar.
# - Cada examples/invalid-* debe fallar.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
EVENTS_DIR="$ROOT/docs/contracts/events"

if [[ ! -f "$EVENTS_DIR/inventory-stock-changed.schema.json" ]]; then
  echo "FAIL: falta docs/contracts/events/inventory-stock-changed.schema.json"
  exit 1
fi

docker run --rm -v "$EVENTS_DIR:/events:z" -w /events python:3.13-slim sh -c '
  pip install --quiet --root-user-action=ignore check-jsonschema==0.38.2 >/dev/null 2>&1
  SCHEMA=inventory-stock-changed.schema.json
  status=0

  if check-jsonschema --check-metaschema "$SCHEMA" >/dev/null; then
    echo "PASS: schema cumple el metaschema"
  else
    echo "FAIL: schema no cumple el metaschema"; status=1
  fi

  if check-jsonschema --schemafile "$SCHEMA" examples/valid.json >/dev/null; then
    echo "PASS: examples/valid.json es valido"
  else
    echo "FAIL: examples/valid.json deberia ser valido"; status=1
  fi

  found=0
  for f in examples/invalid-*; do
    [ -e "$f" ] || continue
    found=1
    if check-jsonschema --schemafile "$SCHEMA" --default-filetype json "$f" >/dev/null 2>&1; then
      echo "FAIL: $f deberia ser invalido"; status=1
    else
      echo "PASS: $f es invalido"
    fi
  done
  if [ "$found" -eq 0 ]; then
    echo "FAIL: no hay ejemplos invalidos"; status=1
  fi

  exit $status
'
