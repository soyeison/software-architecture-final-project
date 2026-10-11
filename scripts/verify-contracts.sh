#!/usr/bin/env bash
# Ejecuta todas las verificaciones de contratos y resume el resultado.
set -uo pipefail

ROOT="$(git rev-parse --show-toplevel)"
CHECKS=(
  "lint-openapi.sh"
  "validate-event-schema.sh"
  "verify-rabbitmq-topology.sh"
)

failed=()
for check in "${CHECKS[@]}"; do
  echo "=== $check ==="
  if ! "$ROOT/scripts/contracts/$check"; then
    failed+=("$check")
  fi
  echo
done

if (( ${#failed[@]} > 0 )); then
  echo "Contratos: FALLÓ (${failed[*]})"
  exit 1
fi
echo "Contratos: OK"
