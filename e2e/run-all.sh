#!/usr/bin/env bash
# Ejecuta los escenarios E2E en orden y resume el resultado (spec E2E §4). Cada escenario corre como subproceso
# aislado y la ejecución continúa tras un fallo; el código de salida es distinto de cero si alguno falló. Los
# escenarios 05 y 08 detienen contenedores y el 08 corta todos los consumidores: nunca se ejecutan en paralelo.
# --reset  reinicia antes el stack con e2e/reset.sh (DESTRUCTIVO: borra los datos del stack de desarrollo)
# --report escribe el resumen en Markdown en el archivo indicado (opcional)
# Uso: e2e/run-all.sh [--reset] [--report <archivo>]
set -uo pipefail
E2E_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$E2E_DIR/lib.sh"
source "$E2E_DIR/report.sh"

usage() { echo "Uso: $0 [--reset] [--report <archivo>]" >&2; }

reset=0
report_file=""
while (( $# > 0 )); do
  case "$1" in
    --reset) reset=1 ;;
    --report)
      (( $# >= 2 )) || { usage; exit 2; }
      report_file="$2"
      shift
      ;;
    *) usage; exit 2 ;;
  esac
  shift
done

if (( reset )); then
  "$E2E_DIR/reset.sh" || { echo "FAIL: no se pudo reiniciar el stack"; exit 1; }
fi
require_stack

scenarios=("$E2E_DIR"/[0-9][0-9]-*.sh)
(( ${#scenarios[@]} == 11 )) || { echo "FAIL: se esperaban 11 escenarios y hay ${#scenarios[@]}"; exit 1; }
for script in "${scenarios[@]}"; do
  run_scenario "$script"
done
render_summary

if [[ -n "$report_file" ]]; then
  mkdir -p "$(dirname "$report_file")"
  render_run_report "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$report_file"
  echo "Informe escrito en $report_file"
fi
(( $(count_passed) == ${#RESULT_NAMES[@]} ))
