#!/usr/bin/env bash
# Devuelve el stack de desarrollo a un estado inicial conocido (spec E2E §4): borra contenedores y volúmenes
# (stock sembrado, colas y outbox vacíos) y lo vuelve a levantar. DESTRUCTIVO para los datos del stack.
# Uso: e2e/reset.sh [--no-build]
set -uo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

(( $# > 1 )) && { echo "Uso: $0 [--no-build]" >&2; exit 2; }
build_flag="--build"
case "${1:-}" in
  "") ;;
  --no-build) build_flag="" ;;
  *) echo "Uso: $0 [--no-build]" >&2; exit 2 ;;
esac

echo "== Reinicio del stack (se borran los datos) =="
compose down -v --remove-orphans || exit 1
# shellcheck disable=SC2086  # build_flag vacío no debe pasar un argumento
compose up -d --wait --wait-timeout 300 $build_flag || exit 1
require_stack
echo "Stack listo: Inventario $INVENTORY_URL, Catálogo $CATALOG_URL"
